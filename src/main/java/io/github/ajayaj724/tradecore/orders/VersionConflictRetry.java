package io.github.ajayaj724.tradecore.orders;

import org.springframework.dao.OptimisticLockingFailureException;

/**
 * A partial market fill emits TradeExecuted and OrderCancelled for the same order in one engine
 * action; their async listeners race on the row's {@code @Version} and the loser's transaction is
 * already rollback-only, so it must be retried from OUTSIDE the transaction boundary — each
 * attempt here runs the transactional method afresh. If retries are exhausted the conflict
 * propagates and the event registry's restart-republish remains the terminal backstop.
 */
final class VersionConflictRetry {

    private static final int MAX_ATTEMPTS = 3;

    private VersionConflictRetry() {}

    static void run(Runnable transactionalOperation) {
        for (int attempt = 1; ; attempt++) {
            try {
                transactionalOperation.run();
                return;
            } catch (OptimisticLockingFailureException conflict) {
                if (attempt == MAX_ATTEMPTS) {
                    throw conflict;
                }
            }
        }
    }
}
