package com.company.personnelarchive.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalLogTest {
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void emitsImmediatelyOutsideATransaction() {
        AtomicBoolean emitted = new AtomicBoolean();

        OperationalLog.afterCommit(() -> emitted.set(true));

        assertThat(emitted).isTrue();
    }

    @Test
    void waitsUntilTransactionCommitBeforeEmittingSuccess() {
        AtomicBoolean emitted = new AtomicBoolean();
        TransactionSynchronizationManager.initSynchronization();

        OperationalLog.afterCommit(() -> emitted.set(true));

        assertThat(emitted).isFalse();
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(synchronization -> synchronization.afterCommit());
        assertThat(emitted).isTrue();
    }

    @Test
    void reportsNonnegativeElapsedMilliseconds() {
        long started = OperationalLog.started();

        assertThat(OperationalLog.elapsedMillis(started)).isNotNegative();
    }
}
