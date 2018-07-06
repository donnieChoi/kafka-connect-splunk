/*
 * Copyright 2017 Splunk, Inc..
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.splunk.kafka.connect;

import com.splunk.hecclient.*;
import com.splunk.kafka.connect.VersionUtils;

import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SplunkSinkTask extends SinkTask implements PollerCallback {
    private static final Logger log = LoggerFactory.getLogger(SplunkSinkTask.class);
    private static final long flushWindow = 30 * 1000; // 30 seconds

    private HecInf hec;
    private KafkaRecordTracker tracker;
    private SplunkSinkConnectorConfig connectorConfig;
    private List<SinkRecord> bufferedRecords;
    private long lastFlushed = System.currentTimeMillis();
    private long threadId = Thread.currentThread().getId();

    @Override
    public void start(Map<String, String> taskConfig) {
        connectorConfig = new SplunkSinkConnectorConfig(taskConfig);
        if (hec == null) {
            hec = createHec();
        }
        tracker = new KafkaRecordTracker();
        bufferedRecords = new ArrayList<>();

        log.info("kafka-connect-splunk task starts with config={}", connectorConfig);
    }

    @Override
    public void put(Collection<SinkRecord> records) {
        long startTime = System.currentTimeMillis();
        log.debug("tid={} received {} records with total {} outstanding events tracked", threadId, records.size(), tracker.totalEvents());

        handleFailedBatches();

        preventTooManyOutstandingEvents();

        bufferedRecords.addAll(records);
        if (bufferedRecords.size() < connectorConfig.maxBatchSize) {
            if (System.currentTimeMillis() - lastFlushed < flushWindow) {
                logDuration(startTime);
                // still in flush window, buffer the records and return
                return;
            }

            if (bufferedRecords.isEmpty()) {
                lastFlushed = System.currentTimeMillis();
                logDuration(startTime);
                return;
            }
        }

        // either flush window reached or max batch size reached
        records = bufferedRecords;
        bufferedRecords = new ArrayList<>();
        lastFlushed = System.currentTimeMillis();

        if (connectorConfig.raw) {
            /* /raw endpoint */
            handleRaw(records);
        } else {
            /* /event endpoint */
            handleEvent(records);
        }
        logDuration(startTime);
    }

    private void logDuration(long startTime) {
        long endTime = System.currentTimeMillis();
        log.debug("tid={} cost={} ms", threadId, endTime - startTime);
    }

    // for testing hook
    SplunkSinkTask setHec(final HecInf hec) {
        this.hec = hec;
        return this;
    }

    KafkaRecordTracker getTracker() {
        return tracker;
    }

    private void handleFailedBatches() {
        Collection<EventBatch> failed = tracker.getAndRemoveFailedRecords();
        if (failed.isEmpty()) {
            return;
        }

        log.debug("going to handle {} failed batches", failed.size());
        long failedEvents = 0;
        // if there are failed ones, first deal with them
        for (final EventBatch batch: failed) {
            failedEvents += batch.size();
            if (connectorConfig.maxRetries > 0 && batch.getFailureCount() > connectorConfig.maxRetries) {
                log.error("dropping EventBatch with {} events in it since it reaches max retries {}",
                        batch.size(), connectorConfig.maxRetries);
                continue;
            }
            send(batch);
        }

        log.info("handled {} failed batches with {} events", failed.size(), failedEvents);
        if (failedEvents * 10 > connectorConfig.maxOutstandingEvents) {
            String msg = String.format("failed events reach 10 %% of max outstanding events %d, pause the pull for a while", connectorConfig.maxOutstandingEvents);
            throw new RetriableException(new HecException(msg));
        }
    }

    private void preventTooManyOutstandingEvents() {
        if (tracker.totalEvents() >= connectorConfig.maxOutstandingEvents) {
            String msg = String.format("max outstanding events %d have reached, pause the pull for a while", connectorConfig.maxOutstandingEvents);
            throw new RetriableException(new HecException(msg));
        }
    }

    private void handleRaw(final Collection<SinkRecord> records) {
  
      Map<EventBatch, Collection<SinkRecord>> partitionedRecords = partitionRecords(records);
      // partition records based on calculated index, source, sourcetype, host      
      for (Map.Entry<EventBatch, Collection<SinkRecord>> entry : partitionedRecords.entrySet()) {
        sendEvents(entry.getValue(), entry.getKey());
      }
  
    }

    private void handleEvent(final Collection<SinkRecord> records) {
        EventBatch batch = new JsonEventBatch();
        sendEvents(records, batch);
    }

    private void sendEvents(final Collection<SinkRecord> records, EventBatch batch) {
        for (final SinkRecord record: records) {
            Event event;
            try {
                event = createHecEventFrom(record);
            } catch (HecException ex) {
                log.error("ignore malformed event for topicPartitionOffset=({}, {}, {})",
                        record.topic(), record.kafkaPartition(), record.kafkaOffset(), ex);
                event = createHecEventFromMalformed(record);
            }

            batch.add(event);
            if (batch.size() >= connectorConfig.maxBatchSize) {
                send(batch);
                // start a new batch after send
                batch = batch.createFromThis();
            }
        }

        // Last batch
        if (!batch.isEmpty()) {
            send(batch);
        }
    }

    private void send(final EventBatch batch) {
        batch.resetSendTimestamp();
        tracker.addEventBatch(batch);
        try {
            hec.send(batch);
        } catch (Exception ex) {
            batch.fail();
            onEventFailure(Arrays.asList(batch), ex);
            log.error("failed to send batch", ex);
        }
    }

    @Override
    public Map<TopicPartition, OffsetAndMetadata> preCommit(Map<TopicPartition, OffsetAndMetadata> meta) {
        // tell Kafka Connect framework what are offsets we can safely commit to Kafka now
        Map<TopicPartition, OffsetAndMetadata> offsets = tracker.computeOffsets();
        log.debug("commits offsets offered={}, pushed={}", offsets, meta);
        return offsets;
    }

    @Override
    public void stop() {
        if (hec != null) {
            hec.close();
        }
        log.info("kafka-connect-splunk task ends with config={}", connectorConfig);
    }

    @Override
    public String version() {
        return VersionUtils.getVersionString();
    }

    public void onEventCommitted(final List<EventBatch> batches) {
        // for (final EventBatch batch: batches) {
            // assert batch.isCommitted();
        // }
    }

    public void onEventFailure(final List<EventBatch> batches, Exception ex) {
        log.info("add {} failed batches", batches.size());
        for (EventBatch batch: batches) {
            tracker.addFailedEventBatch(batch);
        }
    }

    private Event createHecEventFrom(final SinkRecord record) {
        if (connectorConfig.raw) {
            RawEvent event = new RawEvent(record.value(), record);
            event.setLineBreaker(connectorConfig.lineBreaker);
            return event;
        }

        // meta data for /event endpoint is per event basis
        JsonEvent event = new JsonEvent(record.value(), record);
        if (connectorConfig.useRecordTimestamp && record.timestamp() != null) {
            // record timestamp is in milliseconds
            event.setTime(record.timestamp() / 1000.0);
        }

        
        Map<String, String> metas = connectorConfig.topicMetas.get(record.topic());
        if (metas != null) {
            event.setIndex(metas.get(SplunkSinkConnectorConfig.INDEX));
            event.setSourcetype(metas.get(SplunkSinkConnectorConfig.SOURCETYPE));
            event.setSource(metas.get(SplunkSinkConnectorConfig.SOURCE));
            event.setHost(metas.get(SplunkSinkConnectorConfig.HOST));
            event.addFields(connectorConfig.enrichments);
        }
        
        //overwrite with values from headers 
        if (record.headers().lastWithName(SplunkSinkConnectorConfig.INDEX_HDR) != null) {
          event.setIndex(record.headers().lastWithName(SplunkSinkConnectorConfig.INDEX_HDR).value().toString());
        }
        if (record.headers().lastWithName(SplunkSinkConnectorConfig.SOURCETYPE_HDR) != null) {
          event.setSourcetype(record.headers().lastWithName(SplunkSinkConnectorConfig.SOURCETYPE_HDR).value().toString());
        }
        if (record.headers().lastWithName(SplunkSinkConnectorConfig.SOURCE_HDR) != null) {
          event.setSource(record.headers().lastWithName(SplunkSinkConnectorConfig.SOURCE_HDR).value().toString());
        }
        if (record.headers().lastWithName(SplunkSinkConnectorConfig.HOST_HDR) != null) {
          event.setHost(record.headers().lastWithName(SplunkSinkConnectorConfig.HOST_HDR).value().toString());
        }
        if (record.headers().lastWithName(SplunkSinkConnectorConfig.TIME_HDR) != null) {
          long time = Long.valueOf(record.headers().lastWithName(SplunkSinkConnectorConfig.TIME_HDR).value().toString());
          event.setTime(time/1000);
          
        }
        
        

        if (connectorConfig.trackData) {
            // for data loss, latency tracking
            Map<String, String> trackMetas = new HashMap<>();
            trackMetas.put("kafka_offset", String.valueOf(record.kafkaOffset()));
            trackMetas.put("kafka_timestamp", String.valueOf(record.timestamp()));
            trackMetas.put("kafka_topic", record.topic());
            trackMetas.put("kafka_partition", String.valueOf(record.kafkaPartition()));
            event.addFields(trackMetas);
        }

        event.validate();

        return event;
    }

    private Event createHecEventFromMalformed(final SinkRecord record) {
        Object data;
        if (connectorConfig.raw) {
            data = "timestamp=" + record.timestamp() + ", topic='" + record.topic() + '\'' +
                    ", partition=" + record.kafkaPartition() +
                    ", offset=" + record.kafkaOffset() + ", type=malformed";
        } else {
            Map<String, Object> v = new HashMap<>();
            v.put("timestamp", record.timestamp());
            v.put("topic", record.topic());
            v.put("partition", record.kafkaPartition());
            v.put("offset", record.kafkaOffset());
            v.put("type", "malformed");
            data = v;
        }

        final SinkRecord r = record.newRecord("malformed", 0, null, null, null, data, record.timestamp());
        return createHecEventFrom(r);
    }

    // partition records according to topic-partition key
    private Map<EventBatch, Collection<SinkRecord>> partitionRecords(Collection<SinkRecord> records) {
      Map<EventBatch, Collection<SinkRecord>> partitionedRecords = new HashMap<>();
  
      for (SinkRecord record : records) {
        EventBatch batch = getBatchForRecord(record);
        Collection<SinkRecord> partitioned = partitionedRecords.get(batch);
        if (partitioned == null) {
          partitioned = new ArrayList<>();
          partitionedRecords.put(batch, partitioned);
        }
        partitioned.add(record);
      }
      return partitionedRecords;
    }

    private EventBatch getBatchForRecord(SinkRecord record) {
      //get metadata in the order: 
      // 1. defaults (splunk.index, splunk.sourcetype, splunk.source etc )
      // 2. configured topic metas
      // 3. from record headers
      
      String index = connectorConfig.index;
      String sourcetype= connectorConfig.sourcetype;
      String source= connectorConfig.source;
      String host= connectorConfig.host;
      
      
      if (StringUtils.isNotBlank(connectorConfig.topicMetas.getOrDefault(record.topic(), Collections.emptyMap()).getOrDefault(SplunkSinkConnectorConfig.INDEX, null))) {
        index = connectorConfig.topicMetas.getOrDefault(record.topic(), Collections.emptyMap()).getOrDefault(SplunkSinkConnectorConfig.INDEX, null);
      }
      if (StringUtils.isNotBlank(connectorConfig.topicMetas.getOrDefault(record.topic(), Collections.emptyMap()).getOrDefault(SplunkSinkConnectorConfig.SOURCETYPE, null))) {
        sourcetype = connectorConfig.topicMetas.getOrDefault(record.topic(), Collections.emptyMap()).getOrDefault(SplunkSinkConnectorConfig.SOURCETYPE, null);
      }

      if (StringUtils.isNotBlank(connectorConfig.topicMetas.getOrDefault(record.topic(), Collections.emptyMap()).getOrDefault(SplunkSinkConnectorConfig.SOURCE, null))) {
        source = connectorConfig.topicMetas.getOrDefault(record.topic(), Collections.emptyMap()).getOrDefault(SplunkSinkConnectorConfig.SOURCE, null);
      }
      if (StringUtils.isNotBlank(connectorConfig.topicMetas.getOrDefault(record.topic(), Collections.emptyMap()).getOrDefault(SplunkSinkConnectorConfig.HOST, null))) {
        host = connectorConfig.topicMetas.getOrDefault(record.topic(), Collections.emptyMap()).getOrDefault(SplunkSinkConnectorConfig.HOST, null);
      }
      
      if (record.headers().lastWithName(SplunkSinkConnectorConfig.INDEX_HDR) != null) {
        index = record.headers().lastWithName(SplunkSinkConnectorConfig.INDEX_HDR).value().toString();
      }
      if (record.headers().lastWithName(SplunkSinkConnectorConfig.SOURCETYPE_HDR) != null) {
        sourcetype = record.headers().lastWithName(SplunkSinkConnectorConfig.SOURCETYPE_HDR).value().toString();
      }
      if (record.headers().lastWithName(SplunkSinkConnectorConfig.SOURCE_HDR) != null) {
        source = record.headers().lastWithName(SplunkSinkConnectorConfig.SOURCE_HDR).value().toString();
      }
      if (record.headers().lastWithName(SplunkSinkConnectorConfig.HOST_HDR) != null) {
        host = record.headers().lastWithName(SplunkSinkConnectorConfig.HOST_HDR).value().toString();
      }
      
      long time = -1;
      if (record.headers().lastWithName(SplunkSinkConnectorConfig.TIME_HDR) != null) {
        time = Long.valueOf(record.headers().lastWithName(SplunkSinkConnectorConfig.TIME_HDR).value().toString());        
      }
        
        
      return RawEventBatch.factory()
       .setHost(host)
       .setIndex(index)
       .setSource(source)
       .setSourcetype(sourcetype)
       .setTime(time)
       .build();
    }

    private HecInf createHec() {
        if (connectorConfig.numberOfThreads > 1) {
            return new ConcurrentHec(connectorConfig.numberOfThreads, connectorConfig.ack,
                    connectorConfig.getHecConfig(), this);
        } else {
            if (connectorConfig.ack) {
                return Hec.newHecWithAck(connectorConfig.getHecConfig(), this);
            } else {
                return Hec.newHecWithoutAck(connectorConfig.getHecConfig(), this);
            }
        }
    }
}
