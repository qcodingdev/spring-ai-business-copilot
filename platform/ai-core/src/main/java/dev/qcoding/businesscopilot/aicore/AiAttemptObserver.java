package dev.qcoding.businesscopilot.aicore;

/**
 * Per-provider-attempt hook used by the task runtime to reserve budget before dispatch and
 * finalize the durable ledger afterwards. A language retry is a separate observed attempt.
 */
public interface AiAttemptObserver {

    /** Called before a provider request. Returning normally authorizes the dispatch. */
    String beforeAttempt(String operation, String provider, String model, int estimatedTokens);

    /** Called exactly once after an authorized attempt, including provider failures. */
    void afterAttempt(String attemptId, AiInvocationMetadata metadata, Throwable failure);

    static AiAttemptObserver noOp() {
        return NoOpHolder.INSTANCE;
    }

    final class NoOpHolder {
        private static final AiAttemptObserver INSTANCE = new AiAttemptObserver() {
            @Override
            public String beforeAttempt(String operation, String provider, String model,
                                        int estimatedTokens) {
                return null;
            }

            @Override
            public void afterAttempt(String attemptId, AiInvocationMetadata metadata,
                                     Throwable failure) {
                // Standalone calls do not have a task ledger.
            }
        };

        private NoOpHolder() {
        }
    }
}
