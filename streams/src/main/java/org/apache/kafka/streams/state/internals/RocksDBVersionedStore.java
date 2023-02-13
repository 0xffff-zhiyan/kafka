/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.state.internals;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.processor.StateStoreContext;
import org.apache.kafka.streams.processor.internals.ProcessorContextUtils;
import org.apache.kafka.streams.processor.internals.RecordBatchingStateRestoreCallback;
import org.apache.kafka.streams.processor.internals.StoreToProcessorContextAdapter;
import org.apache.kafka.streams.processor.internals.metrics.StreamsMetricsImpl;
import org.apache.kafka.streams.processor.internals.metrics.TaskMetrics;
import org.apache.kafka.streams.query.Position;
import org.apache.kafka.streams.state.VersionedKeyValueStore;
import org.apache.kafka.streams.state.VersionedRecord;
import org.apache.kafka.streams.state.internals.RocksDBVersionedStoreSegmentValueFormatter.SegmentValue;
import org.apache.kafka.streams.state.internals.RocksDBVersionedStoreSegmentValueFormatter.SegmentValue.SegmentSearchResult;
import org.apache.kafka.streams.state.internals.metrics.RocksDBMetricsRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A persistent, versioned key-value store based on RocksDB.
 * <p>
 * This store implementation consists of a "latest value store" and "segment stores." The latest
 * record version for each key is stored in the latest value store, while older record versions
 * are stored in the segment stores. Conceptually, each record version has two associated
 * timestamps:
 * <ul>
 *     <li>a {@code validFrom} timestamp. This timestamp is explicitly associated with the record
 *     as part of the {@link VersionedKeyValueStore#put(Object, Object, long)}} call to the store;
 *     i.e., this is the record's timestamp.</li>
 *     <li>a {@code validTo} timestamp. This is the timestamp of the next record (or deletion)
 *     associated with the same key, and is implicitly associated with the record. This timestamp
 *     can change as new records are inserted into the store.</li>
 * </ul>
 * The validity interval of a record is from validFrom (inclusive) to validTo (exclusive), and
 * can change as new record versions are inserted into the store (and validTo changes as a result).
 * <p>
 * Old record versions are stored in segment stores according to their validTo timestamps. This
 * allows for efficient expiry of old record versions, as entire segments can be dropped from the
 * store at a time, once the records contained in the segment are no longer relevant based on the
 * store's history retention (for an explanation of "history retention", see
 * {@link VersionedKeyValueStore}). Multiple record versions (for the same key) within a single
 * segment are stored together using the format specified in {@link RocksDBVersionedStoreSegmentValueFormatter}.
 */
public class RocksDBVersionedStore implements VersionedKeyValueStore<Bytes, byte[]> {
    private static final Logger LOG = LoggerFactory.getLogger(RocksDBVersionedStore.class);
    // a marker to indicate that no record version has yet been found as part of an ongoing
    // put() procedure. any value which is not a valid record timestamp will do.
    private static final long SENTINEL_TIMESTAMP = Long.MIN_VALUE;

    private final String name;
    private final long historyRetention;
    private final RocksDBMetricsRecorder metricsRecorder;

    private final RocksDBStore latestValueStore;
    private final LogicalKeyValueSegments segmentStores;
    private final VersionedStoreClient<LogicalKeyValueSegment> versionedStoreClient;

    private ProcessorContext context;
    private StateStoreContext stateStoreContext;
    private Sensor expiredRecordSensor;
    private long observedStreamTime = ConsumerRecord.NO_TIMESTAMP;
    private Position position;
    private OffsetCheckpoint positionCheckpoint;
    private volatile boolean open;

    RocksDBVersionedStore(final String name, final String metricsScope, final long historyRetention, final long segmentInterval) {
        this.name = name;
        this.historyRetention = historyRetention;
        this.metricsRecorder = new RocksDBMetricsRecorder(metricsScope, name);
        this.latestValueStore = new RocksDBStore(latestValueStoreName(name), name, metricsRecorder);
        this.segmentStores = new LogicalKeyValueSegments(segmentsStoreName(name), name, historyRetention, segmentInterval, metricsRecorder);
        this.versionedStoreClient = new RocksDBVersionedStoreClient();
    }

    @Override
    public void put(final Bytes key, final byte[] value, final long timestamp) {

        doPut(
            versionedStoreClient,
            Optional.of(expiredRecordSensor),
            key,
            value,
            timestamp
        );

        observedStreamTime = Math.max(observedStreamTime, timestamp);
    }

    @Override
    public VersionedRecord<byte[]> delete(final Bytes key, final long timestamp) {
        final VersionedRecord<byte[]> existingRecord = get(key, timestamp);
        put(key, null, timestamp);
        return existingRecord;
    }

    @Override
    public VersionedRecord<byte[]> get(final Bytes key) {
        // latest value (if present) is guaranteed to be in the latest value store
        final byte[] latestValue = latestValueStore.get(key);
        if (latestValue != null) {
            return new VersionedRecord<>(
                LatestValueFormatter.getValue(latestValue),
                LatestValueFormatter.getTimestamp(latestValue)
            );
        } else {
            return null;
        }
    }

    @Override
    public VersionedRecord<byte[]> get(final Bytes key, final long asOfTimestamp) {

        if (asOfTimestamp < observedStreamTime - historyRetention) {
            // history retention has elapsed. return null for predictability, even if data
            // is still present in store.
            return null;
        }

        // first check the latest value store
        final byte[] rawLatestValueAndTimestamp = latestValueStore.get(key);
        if (rawLatestValueAndTimestamp != null) {
            final long latestTimestamp = LatestValueFormatter.getTimestamp(rawLatestValueAndTimestamp);
            if (latestTimestamp <= asOfTimestamp) {
                return new VersionedRecord<>(LatestValueFormatter.getValue(rawLatestValueAndTimestamp), latestTimestamp);
            }
        }

        // check segment stores
        final List<LogicalKeyValueSegment> segments = segmentStores.segments(asOfTimestamp, Long.MAX_VALUE, false);
        for (final LogicalKeyValueSegment segment : segments) {
            final byte[] rawSegmentValue = segment.get(key);
            if (rawSegmentValue != null) {
                final long nextTs = RocksDBVersionedStoreSegmentValueFormatter.getNextTimestamp(rawSegmentValue);
                if (nextTs <= asOfTimestamp) {
                    // this segment contains no data for the queried timestamp, so earlier segments
                    // cannot either
                    return null;
                }

                if (RocksDBVersionedStoreSegmentValueFormatter.getMinTimestamp(rawSegmentValue) > asOfTimestamp) {
                    // the segment only contains data for after the queried timestamp. skip and
                    // continue the search to earlier segments. as an optimization, this code
                    // could be updated to skip forward to the segment containing the minTimestamp
                    // in the if-condition above.
                    continue;
                }

                // the desired result is contained in this segment
                final SegmentSearchResult searchResult =
                    RocksDBVersionedStoreSegmentValueFormatter
                        .deserialize(rawSegmentValue)
                        .find(asOfTimestamp, true);
                if (searchResult.value() != null) {
                    return new VersionedRecord<>(searchResult.value(), searchResult.validFrom());
                } else {
                    return null;
                }
            }
        }

        // checked all segments and no results found
        return null;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void flush() {
        // order shouldn't matter since failure to flush is a fatal exception
        segmentStores.flush();
        latestValueStore.flush();
    }

    @Override
    public void close() {
        open = false;

        // close latest value store first so that calls to get() immediately begin to fail with
        // store not open, as all calls to get() first get() from latest value store
        latestValueStore.close();
        segmentStores.close();
    }

    @Override
    public boolean persistent() {
        return true;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public Position getPosition() {
        return position;
    }

    @Deprecated
    @Override
    public void init(final ProcessorContext context, final StateStore root) {
        this.context = context;

        final StreamsMetricsImpl metrics = ProcessorContextUtils.getMetricsImpl(context);
        final String threadId = Thread.currentThread().getName();
        final String taskName = context.taskId().toString();

        expiredRecordSensor = TaskMetrics.droppedRecordsSensor(
            threadId,
            taskName,
            metrics
        );

        metricsRecorder.init(ProcessorContextUtils.getMetricsImpl(context), context.taskId());

        latestValueStore.openDB(context.appConfigs(), context.stateDir());
        segmentStores.openExisting(context, observedStreamTime);

        final File positionCheckpointFile = new File(context.stateDir(), name() + ".position");
        this.positionCheckpoint = new OffsetCheckpoint(positionCheckpointFile);
        this.position = StoreQueryUtils.readPositionFromCheckpoint(positionCheckpoint);

        // register and possibly restore the state from the logs
        stateStoreContext.register(
            root,
            (RecordBatchingStateRestoreCallback) RocksDBVersionedStore.this::restoreBatch,
            () -> StoreQueryUtils.checkpointPosition(positionCheckpoint, position)
        );

        open = true;
    }

    @Override
    public void init(final StateStoreContext context, final StateStore root) {
        this.stateStoreContext = context;
        init(StoreToProcessorContextAdapter.adapt(context), root);
    }

    // VisibleForTesting
    void restoreBatch(final Collection<ConsumerRecord<byte[], byte[]>> records) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    /**
     * Generic interface for segment stores. See {@link VersionedStoreClient} for use.
     */
    interface VersionedStoreSegment {

        /**
         * @return segment id
         */
        long id();

        void put(Bytes key, byte[] value);

        byte[] get(Bytes key);
    }

    /**
     * Extracts all operations required for writing to the versioned store (via
     * {@link #put(Bytes, byte[], long)}) into a generic client interface, so that the same
     * {@code put(...)} logic can be shared during regular store operation and during restore.
     *
     * @param <T> the segment type used by this client
     */
    interface VersionedStoreClient<T extends VersionedStoreSegment> {

        /**
         * @return the contents of the latest value store, for the given key
         */
        byte[] getLatestValue(Bytes key);

        /**
         * Puts the provided key and value into the latest value store.
         */
        void putLatestValue(Bytes key, byte[] value);

        /**
         * Deletes the existing value (if any) from the latest value store, for the given key.
         */
        void deleteLatestValue(Bytes key);

        /**
         * @return the segment with the provided id, or {@code null} if the segment is expired
         */
        T getOrCreateSegmentIfLive(long segmentId, ProcessorContext context, long streamTime);

        /**
         * @return all segments in the store which contain timestamps at least the provided
         *         timestamp bound, in reverse order by segment id (and time), i.e., such that
         *         the most recent segment is first
         */
        List<T> getReverseSegments(long timestampFrom);

        /**
         * @return the segment id associated with the provided timestamp
         */
        long segmentIdForTimestamp(long timestamp);
    }

    /**
     * Client for writing into (and reading from) this persistent {@link RocksDBVersionedStore}.
     */
    private class RocksDBVersionedStoreClient implements VersionedStoreClient<LogicalKeyValueSegment> {

        @Override
        public byte[] getLatestValue(final Bytes key) {
            return latestValueStore.get(key);
        }

        @Override
        public void putLatestValue(final Bytes key, final byte[] value) {
            latestValueStore.put(key, value);
        }

        @Override
        public void deleteLatestValue(final Bytes key) {
            latestValueStore.delete(key);
        }

        @Override
        public LogicalKeyValueSegment getOrCreateSegmentIfLive(final long segmentId, final ProcessorContext context, final long streamTime) {
            return segmentStores.getOrCreateSegmentIfLive(segmentId, context, streamTime);
        }

        @Override
        public List<LogicalKeyValueSegment> getReverseSegments(final long timestampFrom) {
            return segmentStores.segments(timestampFrom, Long.MAX_VALUE, false);
        }

        @Override
        public long segmentIdForTimestamp(final long timestamp) {
            return segmentStores.segmentId(timestamp);
        }
    }

    private <T extends VersionedStoreSegment> void doPut(
        final VersionedStoreClient<T> versionedStoreClient,
        final Optional<Sensor> expiredRecordSensor,
        final Bytes key,
        final byte[] value,
        final long timestamp
    ) {
        // track the smallest timestamp seen so far that is larger than insertion timestamp.
        // this timestamp determines, based on all segments searched so far, which segment the
        // new record should be inserted into.
        long foundTs;

        // check latest value store
        PutStatus status = maybePutToLatestValueStore(
            versionedStoreClient,
            key,
            value,
            timestamp
        );
        if (status.isComplete) {
            return;
        } else {
            foundTs = status.foundTs;
        }

        // continue search in segments
        status = maybePutToSegments(
            versionedStoreClient,
            expiredRecordSensor,
            key,
            value,
            timestamp,
            foundTs
        );
        if (status.isComplete) {
            return;
        } else {
            foundTs = status.foundTs;
        }

        // the record did not unconditionally belong in any specific store (latest value store
        // or segments store). insert based on foundTs here instead.
        finishPut(
            versionedStoreClient,
            expiredRecordSensor,
            key,
            value,
            timestamp,
            foundTs
        );
    }

    /**
     * Represents the status of an ongoing put() operation.
     */
    private static class PutStatus {

        /**
         * Whether the put() call has been completed.
         */
        final boolean isComplete;

        /**
         * The smallest timestamp seen so far, as part of this current put() operation, that is
         * larger than insertion timestamp. This timestamp determines, based on all segments
         * searched so far, which segment the new record should be inserted into.
         */
        final long foundTs;

        PutStatus(final boolean isComplete, final long foundTs) {
            this.isComplete = isComplete;
            this.foundTs = foundTs;
        }
    }

    private <T extends VersionedStoreSegment> PutStatus maybePutToLatestValueStore(
        final VersionedStoreClient<T> versionedStoreClient,
        final Bytes key,
        final byte[] value,
        final long timestamp
    ) {
        // initialize with a starting "sentinel timestamp" which represents
        // that the segment should be inserted into the latest value store.
        long foundTs = SENTINEL_TIMESTAMP;

        final byte[] rawLatestValueAndTimestamp = versionedStoreClient.getLatestValue(key);
        if (rawLatestValueAndTimestamp != null) {
            foundTs = LatestValueFormatter.getTimestamp(rawLatestValueAndTimestamp);
            if (timestamp >= foundTs) {
                // new record belongs in the latest value store
                if (timestamp > foundTs) {
                    // move existing latest value into segment.
                    // it's important that this step happens before the update to the latest value
                    // store. if there is a partial failure (this step succeeds but the update to
                    // the latest value store fails), updating the segment first means there will
                    // not be data loss. (rather, there will be duplicated data which is fine as
                    // it can/will be reconciled later.)
                    final long segmentId = versionedStoreClient.segmentIdForTimestamp(timestamp);
                    final T segment = versionedStoreClient.getOrCreateSegmentIfLive(segmentId, context, observedStreamTime);
                    // `segment == null` implies that all data in the segment is older than the
                    // history retention of this store, and therefore does not need to tracked.
                    // as a result, we only need to move the existing record from the latest value
                    // store into a segment if `segment != null`. (also, we do not call the
                    // `expiredRecordSensor` in this case because the expired record sensor is only
                    // called when the record being put is itself expired, which is not the case
                    // here. only the existing record already present in the latest value store
                    // is expired.) so, there is nothing to do for this step if `segment == null`,
                    // but we do still update the latest value store with the new record below.
                    if (segment != null) {
                        final byte[] rawValueToMove = LatestValueFormatter.getValue(rawLatestValueAndTimestamp);
                        final byte[] rawSegmentValue = segment.get(key);
                        if (rawSegmentValue == null) {
                            segment.put(
                                key,
                                RocksDBVersionedStoreSegmentValueFormatter
                                    .newSegmentValueWithRecord(rawValueToMove, foundTs, timestamp)
                                    .serialize()
                            );
                        } else {
                            final SegmentValue segmentValue = RocksDBVersionedStoreSegmentValueFormatter.deserialize(rawSegmentValue);
                            segmentValue.insertAsLatest(foundTs, timestamp, rawValueToMove);
                            segment.put(key, segmentValue.serialize());
                        }
                    }
                }

                // update latest value store
                if (value != null) {
                    versionedStoreClient.putLatestValue(key, LatestValueFormatter.from(value, timestamp));
                } else {
                    versionedStoreClient.deleteLatestValue(key);
                }
                return new PutStatus(true, foundTs);
            }
        }
        return new PutStatus(false, foundTs);
    }

    private <T extends VersionedStoreSegment> PutStatus maybePutToSegments(
        final VersionedStoreClient<T> versionedStoreClient,
        final Optional<Sensor> expiredRecordSensor,
        final Bytes key,
        final byte[] value,
        final long timestamp,
        final long prevFoundTs
    ) {
        // initialize with current foundTs value
        long foundTs = prevFoundTs;

        final List<T> segments = versionedStoreClient.getReverseSegments(timestamp);
        for (final T segment : segments) {
            final byte[] rawSegmentValue = segment.get(key);
            if (rawSegmentValue != null) {
                final long foundNextTs = RocksDBVersionedStoreSegmentValueFormatter.getNextTimestamp(rawSegmentValue);
                if (foundNextTs <= timestamp) {
                    // this segment (and all earlier segments) does not contain records affected by
                    // this put. insert into the segment specified by foundTs (i.e., the next
                    // phase of the put() procedure) and conclude the procedure.
                    return new PutStatus(false, foundTs);
                }

                final long foundMinTs = RocksDBVersionedStoreSegmentValueFormatter.getMinTimestamp(rawSegmentValue);
                if (foundMinTs <= timestamp) {
                    // the record being inserted belongs in this segment.
                    // insert and conclude the procedure.
                    putToSegment(
                        versionedStoreClient,
                        segment,
                        rawSegmentValue,
                        key,
                        value,
                        timestamp
                    );
                    return new PutStatus(true, foundTs);
                }

                if (foundMinTs < observedStreamTime - historyRetention) {
                    // the record being inserted does not affect version history. discard and return
                    if (expiredRecordSensor.isPresent()) {
                        expiredRecordSensor.get().record(1.0d, context.currentSystemTimeMs());
                        LOG.warn("Skipping record for expired put.");
                    }
                    return new PutStatus(true, foundTs);
                }

                // it's possible the record belongs in this segment, but also possible it belongs
                // in an earlier segment. mark as tentative and continue. as an optimization, this
                // code could be updated to skip forward to the segment containing foundMinTs.
                foundTs = foundMinTs;
            }
        }
        return new PutStatus(false, foundTs);
    }

    private <T extends VersionedStoreSegment> void putToSegment(
        final VersionedStoreClient<T> versionedStoreClient,
        final T segment,
        final byte[] rawSegmentValue,
        final Bytes key,
        final byte[] value,
        final long timestamp
    ) {
        final long segmentIdForTimestamp = versionedStoreClient.segmentIdForTimestamp(timestamp);
        // it's possible that putting the current record into a segment will require moving an
        // existing record from this segment into an older segment. this is because records belong
        // in segments based on their validTo timestamps, and putting a new record into a segment
        // updates the validTo timestamp for the previous record. the new validTo timestamp
        // of the previous record will be the (validFrom) timestamp of the record being put.
        // if this (validFrom) timestamp belongs in a different segment, then it may be required
        // to move an existing record from this segment into an older one.
        final boolean writeToOlderSegmentMaybeNeeded = segmentIdForTimestamp != segment.id();

        final SegmentValue segmentValue = RocksDBVersionedStoreSegmentValueFormatter.deserialize(rawSegmentValue);
        // we pass `writeToOlderSegmentMaybeNeeded` as `includeValue` in the call to find() below
        // because moving a record from this segment into an older one will require knowing its
        // value. unless such a move is required, we do not need the existing record value.
        final SegmentSearchResult searchResult = segmentValue.find(timestamp, writeToOlderSegmentMaybeNeeded);

        if (searchResult.validFrom() == timestamp) {
            // this put() replaces an existing entry, rather than adding a new one. as a result,
            // the validTo timestamp of the previous record does not need to be updated either,
            // and we do not need to move an existing record to an older segment (i.e., we can
            // ignore `writeToOlderSegmentMaybeNeeded`).
            segmentValue.updateRecord(timestamp, value, searchResult.index());
            segment.put(key, segmentValue.serialize());
            return;
        }

        if (writeToOlderSegmentMaybeNeeded) {
            // existing record needs to be moved to an older segment.
            // it's important that this step happens before updating the current
            // segment. if there is a partial failure (this step succeeds but the
            // update to the current segment fails), updating the older segment
            // first means there will not be data loss. (rather, there will be
            // duplicated data which is fine as it can/will be reconciled later.)
            final T olderSegment = versionedStoreClient
                .getOrCreateSegmentIfLive(segmentIdForTimestamp, context, observedStreamTime);
            // `olderSegment == null` implies that all data in the older segment is older than the
            // history retention of this store, and therefore does not need to tracked.
            // as a result, we only need to move the existing record from the newer segment
            // into the older segment if `olderSegment != null`. (also, we do not call the
            // `expiredRecordSensor` in this case because the expired record sensor is only
            // called when the record being put is itself expired, which is not the case
            // here. only the existing record already present in the newer segment is
            // expired.) so, there is nothing to do for this step if `olderSegment == null`,
            // but we do still update the newer segment with the new record below.
            if (olderSegment != null) {
                final byte[] rawOlderSegmentValue = olderSegment.get(key);
                if (rawOlderSegmentValue == null) {
                    olderSegment.put(
                        key,
                        RocksDBVersionedStoreSegmentValueFormatter.newSegmentValueWithRecord(
                            searchResult.value(), searchResult.validFrom(), timestamp
                        ).serialize()
                    );
                } else {
                    final SegmentValue olderSegmentValue
                        = RocksDBVersionedStoreSegmentValueFormatter.deserialize(rawOlderSegmentValue);
                    olderSegmentValue.insertAsLatest(searchResult.validFrom(), timestamp, searchResult.value());
                    olderSegment.put(key, olderSegmentValue.serialize());
                }
            }

            // update in newer segment (replace the record that was just moved with the new one)
            segmentValue.updateRecord(timestamp, value, searchResult.index());
            segment.put(key, segmentValue.serialize());
            return;
        }

        // plain insert into segment. no additional handling required.
        segmentValue.insert(timestamp, value, searchResult.index());
        segment.put(key, segmentValue.serialize());
    }

    private <T extends VersionedStoreSegment> void finishPut(
        final VersionedStoreClient<T> versionedStoreClient,
        final Optional<Sensor> expiredRecordSensor,
        final Bytes key,
        final byte[] value,
        final long timestamp,
        final long foundTs
    ) {
        if (foundTs == SENTINEL_TIMESTAMP) {
            // insert into latest value store
            if (value != null) {
                versionedStoreClient.putLatestValue(key, LatestValueFormatter.from(value, timestamp));
            } else {
                // tombstones are not inserted into the latest value store. insert into segment instead.
                // the specific segment to insert to is determined based on the tombstone's timestamp
                final T segment = versionedStoreClient.getOrCreateSegmentIfLive(
                    versionedStoreClient.segmentIdForTimestamp(timestamp), context, observedStreamTime);
                if (segment == null) {
                    if (expiredRecordSensor.isPresent()) {
                        expiredRecordSensor.get().record(1.0d, context.currentSystemTimeMs());
                        LOG.warn("Skipping record for expired put.");
                    }
                    return;
                }

                final byte[] rawSegmentValue = segment.get(key);
                if (rawSegmentValue == null) {
                    // in this special case where the latest record version (for a particular key)
                    // is a tombstone, and the segment that the tombstone belongs in contains no
                    // record versions for this key, create a new "degenerate" segment with the
                    // tombstone's timestamp as both validFrom and validTo timestamps for the segment
                    segment.put(
                        key,
                        RocksDBVersionedStoreSegmentValueFormatter
                            .newSegmentValueWithRecord(null, timestamp, timestamp)
                            .serialize()
                    );
                } else {
                    // insert as latest, since foundTs = sentinel means nothing later exists
                    if (RocksDBVersionedStoreSegmentValueFormatter.getNextTimestamp(rawSegmentValue) == timestamp) {
                        // next timestamp equal to put() timestamp already represents a tombstone,
                        // so no additional insertion is needed in this case
                        return;
                    }
                    final SegmentValue segmentValue
                        = RocksDBVersionedStoreSegmentValueFormatter.deserialize(rawSegmentValue);
                    segmentValue.insertAsLatest(
                        RocksDBVersionedStoreSegmentValueFormatter.getNextTimestamp(rawSegmentValue),
                        timestamp,
                        null
                    );
                    segment.put(key, segmentValue.serialize());
                }
            }
        } else {
            // insert into segment corresponding to foundTs, as foundTs represents the validTo
            // timestamp of the current put.
            // the new record is either the earliest or the latest in this segment, depending on the
            // circumstances of the fall-through. (it cannot belong in the middle because otherwise
            // putSegments() above would have identified a segment for which
            // minTimestamp <= timestamp < nextTimestamp, and putSegments would've completed the
            // put procedure without reaching this fall-through case.)
            final T segment = versionedStoreClient.getOrCreateSegmentIfLive(
                versionedStoreClient.segmentIdForTimestamp(foundTs), context, observedStreamTime);
            if (segment == null) {
                if (expiredRecordSensor.isPresent()) {
                    expiredRecordSensor.get().record(1.0d, context.currentSystemTimeMs());
                    LOG.warn("Skipping record for expired put.");
                }
                return;
            }

            final byte[] rawSegmentValue = segment.get(key);
            if (rawSegmentValue == null) {
                segment.put(
                    key,
                    RocksDBVersionedStoreSegmentValueFormatter
                        .newSegmentValueWithRecord(value, timestamp, foundTs)
                        .serialize()
                );
            } else {
                final long foundNextTs = RocksDBVersionedStoreSegmentValueFormatter.getNextTimestamp(rawSegmentValue);
                if (foundNextTs <= timestamp) {
                    // insert as latest. this case is possible if the found segment is "degenerate"
                    // (cf RocksDBVersionedStoreSegmentValueFormatter.java for details) as older
                    // degenerate segments may result in "gaps" between one record version's validTo
                    // timestamp and the next record version's validFrom timestamp
                    final SegmentValue segmentValue = RocksDBVersionedStoreSegmentValueFormatter.deserialize(rawSegmentValue);
                    segmentValue.insertAsLatest(timestamp, foundTs, value);
                    segment.put(key, segmentValue.serialize());
                } else {
                    // insert as earliest
                    final SegmentValue segmentValue = RocksDBVersionedStoreSegmentValueFormatter.deserialize(rawSegmentValue);
                    segmentValue.insertAsEarliest(timestamp, value);
                    segment.put(key, segmentValue.serialize());
                }
            }
        }
    }

    /**
     * Bytes layout for the value portion of rows stored in the latest value store. The layout is
     * a fixed-size timestamp concatenated with the actual record value.
     */
    private static final class LatestValueFormatter {

        private static final int TIMESTAMP_SIZE = 8;

        /**
         * @return the timestamp, from the latest value store value bytes (representing value
         *         and timestamp)
         */
        static long getTimestamp(final byte[] rawLatestValueAndTimestamp) {
            return ByteBuffer.wrap(rawLatestValueAndTimestamp).getLong();
        }

        /**
         * @return the actual record value, from the latest value store value bytes (representing
         *         value and timestamp)
         */
        static byte[] getValue(final byte[] rawLatestValueAndTimestamp) {
            final byte[] rawValue = new byte[rawLatestValueAndTimestamp.length - TIMESTAMP_SIZE];
            System.arraycopy(rawLatestValueAndTimestamp, TIMESTAMP_SIZE, rawValue, 0, rawValue.length);
            return rawValue;
        }

        /**
         * @return the formatted bytes containing the provided {@code rawValue} and
         *         {@code timestamp}, ready to be stored into the latest value store
         */
        static byte[] from(final byte[] rawValue, final long timestamp) {
            if (rawValue == null) {
                throw new IllegalStateException("Cannot store tombstone in latest value");
            }

            return ByteBuffer.allocate(TIMESTAMP_SIZE + rawValue.length)
                .putLong(timestamp)
                .put(rawValue)
                .array();
        }
    }

    private static String latestValueStoreName(final String storeName) {
        return storeName + ".latestValues";
    }

    private static String segmentsStoreName(final String storeName) {
        return storeName + ".segments";
    }
}