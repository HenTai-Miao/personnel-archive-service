/* 运行日志辅助：使用单调时钟，并让事务成功事件只在提交后发出。 */
package com.company.personnelarchive.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;

public final class OperationalLog {
    private OperationalLog() {}

    public static long started() {
        return System.nanoTime();
    }

    public static long elapsedMillis(long started) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    public static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
