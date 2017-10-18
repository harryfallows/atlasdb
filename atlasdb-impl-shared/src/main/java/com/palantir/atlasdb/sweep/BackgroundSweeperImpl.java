/*
 * Copyright 2015 Palantir Technologies, Inc. All rights reserved.
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
package com.palantir.atlasdb.sweep;

import static com.palantir.atlasdb.sweep.BackgroundSweeperImpl.SweepOutcome.DISABLED;
import static com.palantir.atlasdb.sweep.BackgroundSweeperImpl.SweepOutcome.ERROR;
import static com.palantir.atlasdb.sweep.BackgroundSweeperImpl.SweepOutcome.NOTHING_TO_SWEEP;
import static com.palantir.atlasdb.sweep.BackgroundSweeperImpl.SweepOutcome.NOT_ENOUGH_DB_NODES_ONLINE;
import static com.palantir.atlasdb.sweep.BackgroundSweeperImpl.SweepOutcome.RETRYING_WITH_SMALLER_BATCH;
import static com.palantir.atlasdb.sweep.BackgroundSweeperImpl.SweepOutcome.SUCCESS;
import static com.palantir.atlasdb.sweep.BackgroundSweeperImpl.SweepOutcome.TABLE_DROPPED_WHILE_SWEEPING;
import static com.palantir.atlasdb.sweep.BackgroundSweeperImpl.SweepOutcome.UNABLE_TO_ACQUIRE_LOCKS;

import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.palantir.atlasdb.keyvalue.api.InsufficientConsistencyException;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.sweep.priority.NextTableToSweepProvider;
import com.palantir.atlasdb.sweep.priority.NextTableToSweepProviderImpl;
import com.palantir.atlasdb.sweep.progress.SweepProgress;
import com.palantir.atlasdb.transaction.api.Transaction;
import com.palantir.atlasdb.transaction.api.TransactionTask;
import com.palantir.common.base.Throwables;
import com.palantir.lock.LockService;
import com.palantir.logsafe.SafeArg;

public final class BackgroundSweeperImpl implements BackgroundSweeper {
    private static final Logger log = LoggerFactory.getLogger(BackgroundSweeperImpl.class);
    private final LockService lockService;
    private final NextTableToSweepProvider nextTableToSweepProvider;
    private final Supplier<Boolean> isSweepEnabled;
    private final Supplier<Long> sweepPauseMillis;
    private final PersistentLockManager persistentLockManager;
    private final SpecificTableSweeper specificTableSweeper;

    static volatile double batchSizeMultiplier = 1.0;

    private Thread daemon;

    @VisibleForTesting
    BackgroundSweeperImpl(
            LockService lockService,
            NextTableToSweepProvider nextTableToSweepProvider,
            Supplier<Boolean> isSweepEnabled,
            Supplier<Long> sweepPauseMillis,
            PersistentLockManager persistentLockManager,
            SpecificTableSweeper specificTableSweeper) {
        this.lockService = lockService;
        this.nextTableToSweepProvider = nextTableToSweepProvider;
        this.isSweepEnabled = isSweepEnabled;
        this.sweepPauseMillis = sweepPauseMillis;
        this.persistentLockManager = persistentLockManager;
        this.specificTableSweeper = specificTableSweeper;
    }

    public static BackgroundSweeperImpl create(
            Supplier<Boolean> isSweepEnabled,
            Supplier<Long> sweepPauseMillis,
            PersistentLockManager persistentLockManager,
            SpecificTableSweeper specificTableSweeper) {
        NextTableToSweepProvider nextTableToSweepProvider = new NextTableToSweepProviderImpl(
                specificTableSweeper.getKvs(), specificTableSweeper.getSweepPriorityStore());
        return new BackgroundSweeperImpl(
                specificTableSweeper.getTxManager().getLockService(),
                nextTableToSweepProvider,
                isSweepEnabled,
                sweepPauseMillis,
                persistentLockManager,
                specificTableSweeper);
    }

    @Override
    public synchronized void runInBackground() {
        Preconditions.checkState(daemon == null);
        daemon = new Thread(this);
        daemon.setDaemon(true);
        daemon.setName("BackgroundSweeper");
        daemon.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down persistent lock manager");
            try {
                persistentLockManager.shutdown();
                log.info("Shutdown complete!");
            } catch (Exception e) {
                log.warn("An exception occurred while shutting down. This means that we had the backup lock out when"
                         + "the shutdown was triggered, but failed to release it. If this is the case, sweep or backup"
                         + "may fail to take out the lock in future. If this happens consistently, "
                         + "consult the following documentation on how to release the dead lock: "
                         + "https://palantir.github.io/atlasdb/html/troubleshooting/index.html#clearing-the-backup-lock",
                        e);
            }
        }));
    }

    @Override
    public void run() {
        try (SweepLocks locks = createSweepLocks()) {
            // Wait a while before starting so short lived clis don't try to sweep.
            Thread.sleep(getBackoffTimeWhenSweepHasNotRun());
            log.info("Starting background sweeper.");
            while (true) {
                SweepOutcome outcome = checkConfigAndRunSweep(locks);

                updateBatchSizeBasedOn(outcome);

                Thread.sleep(getMillisToSleepBasedOn(outcome));
            }
        } catch (InterruptedException e) {
            log.warn("Shutting down background sweeper. Please restart the service to rerun background sweep.");
        }
    }

    private long getMillisToSleepBasedOn(SweepOutcome outcome) {
        if (outcome == SUCCESS) {
            return sweepPauseMillis.get();
        }
        return getBackoffTimeWhenSweepHasNotRun();
    }

    private void updateBatchSizeBasedOn(SweepOutcome outcome) {
        if (outcome == SUCCESS) {
            batchSizeMultiplier = Math.min(1.0, batchSizeMultiplier * 1.01);
            return;
        }
        if (outcome == RETRYING_WITH_SMALLER_BATCH) {
            SweepBatchConfig lastBatchConfig = specificTableSweeper.getAdjustedBatchConfig();

            // Cut batch size in half, always sweep at least one row (we round down).
            batchSizeMultiplier = Math.max(batchSizeMultiplier / 2, 1.5 / lastBatchConfig.candidateBatchSize());

            log.warn("The background sweep job failed unexpectedly with candidate batch size {},"
                            + " delete batch size {},"
                            + " and {} cell+timestamp pairs to examine."
                            + " Attempting to continue with new batchSizeMultiplier {}",
                    SafeArg.of("candidateBatchSize", lastBatchConfig.candidateBatchSize()),
                    SafeArg.of("deleteBatchSize", lastBatchConfig.deleteBatchSize()),
                    SafeArg.of("maxCellTsPairsToExamine", lastBatchConfig.maxCellTsPairsToExamine()),
                    SafeArg.of("batchSizeMultiplier", batchSizeMultiplier));
            return;
        }
    }

    @VisibleForTesting
    SweepOutcome checkConfigAndRunSweep(SweepLocks locks) throws InterruptedException {
        if (isSweepEnabled.get()) {
            return grabLocksAndRun(locks);
        }

        log.debug("Skipping sweep because it is currently disabled.");
        return DISABLED;
    }

    private SweepOutcome grabLocksAndRun(SweepLocks locks) throws InterruptedException {
        try {
            locks.lockOrRefresh();
            if (locks.haveLocks()) {
                return runOnce();
            } else {
                log.debug("Skipping sweep because sweep is running elsewhere.");
                return UNABLE_TO_ACQUIRE_LOCKS;
            }
        } catch (InsufficientConsistencyException e) {
            log.warn("Could not sweep because not all nodes of the database are online.", e);
            return NOT_ENOUGH_DB_NODES_ONLINE;
        } catch (RuntimeException e) {
            specificTableSweeper.getSweepMetrics().sweepError();

            return determineCauseOfFailure(e);
        }
    }

    private long getBackoffTimeWhenSweepHasNotRun() {
        return 20 * (1000 + sweepPauseMillis.get());
    }

    @VisibleForTesting
    SweepOutcome runOnce() {
        Optional<TableToSweep> tableToSweep = getTableToSweep();
        if (!tableToSweep.isPresent()) {
            // Don't change this log statement. It's parsed by test automation code.
            log.debug("Skipping sweep because no table has enough new writes to be worth sweeping at the moment.");
            return NOTHING_TO_SWEEP;
        } else {
            specificTableSweeper.runOnceAndSaveResults(tableToSweep.get());
            return SUCCESS;
        }
    }

    // there's a bug in older jdk8s around type inference here, don't make the same mistake two of us made
    // and try to lambda refactor this unless you live far enough in the future that this isn't an issue
    private Optional<TableToSweep> getTableToSweep() {
        return specificTableSweeper.getTxManager().runTaskWithRetry(
                new TransactionTask<Optional<TableToSweep>, RuntimeException>() {
                    @Override
                    public Optional<TableToSweep> execute(Transaction tx) {
                        Optional<SweepProgress> progress = specificTableSweeper.getSweepProgressStore().loadProgress(
                                tx);
                        if (progress.isPresent()) {
                            return Optional.of(new TableToSweep(progress.get().tableRef(), progress));
                        } else {
                            Optional<TableReference> nextTable = nextTableToSweepProvider.chooseNextTableToSweep(
                                    tx, specificTableSweeper.getSweepRunner().getConservativeSweepTimestamp());
                            if (nextTable.isPresent()) {
                                return Optional.of(new TableToSweep(nextTable.get(), Optional.empty()));
                            } else {
                                return Optional.empty();
                            }
                        }
                    }
                });
    }

    private SweepOutcome determineCauseOfFailure(Exception e) {
        try {
            Set<TableReference> tables = specificTableSweeper.getKvs().getAllTableNames();
            Optional<SweepProgress> progress = specificTableSweeper.getTxManager().runTaskReadOnly(
                    specificTableSweeper.getSweepProgressStore()::loadProgress);

            if (!progress.isPresent() || tables.contains(progress.get().tableRef())) {
                log.warn("The background sweep job failed unexpectedly; will retry with a lower batch size...", e);
                return RETRYING_WITH_SMALLER_BATCH;
            } else {
                specificTableSweeper.getSweepProgressStore().clearProgress();
                log.info("The table being swept by the background sweeper was dropped, moving on...");
                return TABLE_DROPPED_WHILE_SWEEPING;
            }
        } catch (RuntimeException newE) {
            log.error("Sweep failed", e);
            log.error("Failed to check whether the table being swept was dropped. Retrying...", newE);
            return ERROR;
        }
    }

    @VisibleForTesting
    SweepLocks createSweepLocks() {
        return new SweepLocks(lockService);
    }

    @Override
    public synchronized void shutdown() {
        if (daemon == null) {
            return;
        }
        log.info("Signalling background sweeper to shut down.");
        daemon.interrupt();
        try {
            daemon.join();
            daemon = null;
        } catch (InterruptedException e) {
            throw Throwables.rewrapAndThrowUncheckedException(e);
        }
    }

    enum SweepOutcome {
        SUCCESS, NOTHING_TO_SWEEP, RETRYING_WITH_SMALLER_BATCH, DISABLED, UNABLE_TO_ACQUIRE_LOCKS, NOT_ENOUGH_DB_NODES_ONLINE, TABLE_DROPPED_WHILE_SWEEPING, ERROR
    }
}
