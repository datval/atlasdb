/**
 * Copyright 2015 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.cleaner;

import java.util.List;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.transaction.service.TransactionService;
import com.palantir.common.time.Clock;
import com.palantir.lock.LockClient;
import com.palantir.lock.RemoteLockService;
import com.palantir.timestamp.TimestampService;

public class DefaultCleanerBuilder {
    private final KeyValueService keyValueService;
    private final RemoteLockService lockService;
    private final TimestampService timestampService;
    private final LockClient lockClient;
    private final List<Follower> followerList;
    private final TransactionService transactionService;

    private long transactionReadTimeout = AtlasDbConstants.DEFAULT_TRANSACTION_READ_TIMEOUT;
    private long punchIntervalMillis = AtlasDbConstants.DEFAULT_PUNCH_INTERVAL_MILLIS;
    private boolean backgroundScrubAggressively = AtlasDbConstants.DEFAULT_BACKGROUND_SCRUB_AGGRESSIVELY;
    private int backgroundScrubThreads = AtlasDbConstants.DEFAULT_BACKGROUND_SCRUB_THREADS;
    private int backgroundScrubReadThreads = AtlasDbConstants.DEFAULT_BACKGROUND_SCRUB_READ_THREADS;
    private long backgroundScrubFrequencyMillis = AtlasDbConstants.DEFAULT_BACKGROUND_SCRUB_FREQUENCY_MILLIS;
    private int backgroundScrubBatchSize = AtlasDbConstants.DEFAULT_BACKGROUND_SCRUB_BATCH_SIZE;

    public DefaultCleanerBuilder(KeyValueService keyValueService,
                                 RemoteLockService lockService,
                                 TimestampService timestampService,
                                 LockClient lockClient,
                                 List<? extends Follower> followerList,
                                 TransactionService transactionService) {
        this.keyValueService = keyValueService;
        this.lockService = lockService;
        this.timestampService = timestampService;
        this.lockClient = lockClient;
        this.followerList = ImmutableList.copyOf(followerList);
        this.transactionService = transactionService;
    }

    public DefaultCleanerBuilder setTransactionReadTimeout(long transactionReadTimeout) {
        this.transactionReadTimeout = transactionReadTimeout;
        return this;
    }

    public DefaultCleanerBuilder setPunchIntervalMillis(long punchIntervalMillis) {
        this.punchIntervalMillis = punchIntervalMillis;
        return this;
    }

    public DefaultCleanerBuilder setBackgroundScrubAggressively(boolean backgroundScrubAggressively) {
        this.backgroundScrubAggressively = backgroundScrubAggressively;
        return this;
    }

    public DefaultCleanerBuilder setBackgroundScrubThreads(int backgroundScrubThreads) {
        this.backgroundScrubThreads = backgroundScrubThreads;
        return this;
    }

    public DefaultCleanerBuilder setBackgroundScrubReadThreads(int backgroundScrubReadThreads) {
        this.backgroundScrubReadThreads = backgroundScrubReadThreads;
        return this;
    }

    public DefaultCleanerBuilder setBackgroundScrubFrequencyMillis(long backgroundScrubFrequencyMillis) {
        this.backgroundScrubFrequencyMillis = backgroundScrubFrequencyMillis;
        return this;
    }

    public DefaultCleanerBuilder setBackgroundScrubBatchSize(int backgroundScrubBatchSize) {
        this.backgroundScrubBatchSize = backgroundScrubBatchSize;
        return this;
    }

    private Puncher buildPuncher() {
        KeyValueServicePuncherStore keyValuePuncherStore = KeyValueServicePuncherStore.create(keyValueService);
        PuncherStore cachingPuncherStore = CachingPuncherStore.create(
                keyValuePuncherStore,
                punchIntervalMillis * 3);
        Clock clock = GlobalClock.create(lockService);
        SimplePuncher simplePuncher = SimplePuncher.create(
                cachingPuncherStore,
                clock,
                Suppliers.ofInstance(transactionReadTimeout));
        return AsyncPuncher.create(simplePuncher, punchIntervalMillis);
    }

    private Scrubber buildScrubber(Supplier<Long> unreadableTimestampSupplier,
                                   Supplier<Long> immutableTimestampSupplier) {
        ScrubberStore scrubberStore = KeyValueServiceScrubberStore.create(keyValueService);
        return Scrubber.create(
                keyValueService,
                scrubberStore,
                Suppliers.ofInstance(backgroundScrubFrequencyMillis),
                Suppliers.ofInstance(true),
                unreadableTimestampSupplier,
                immutableTimestampSupplier,
                transactionService,
                backgroundScrubAggressively,
                Suppliers.ofInstance(backgroundScrubBatchSize),
                backgroundScrubThreads,
                backgroundScrubReadThreads,
                followerList);
    }

    public Cleaner buildCleaner() {
        Puncher puncher = buildPuncher();
        Supplier<Long> immutableTs = ImmutableTimestampSupplier
                .createMemoizedWithExpiration(lockService, timestampService, lockClient);
        Scrubber scrubber = buildScrubber(puncher.getTimestampSupplier(), immutableTs);
        return new SimpleCleaner(
                scrubber,
                puncher,
                Suppliers.ofInstance(transactionReadTimeout));
    }
}
