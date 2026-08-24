package com.example.idempotency.store;

public enum IdempotencyStatus {
    /** Request accepted, handler is currently executing. */
    IN_PROGRESS,
    /** Handler finished successfully; response is cached and will be replayed. */
    COMPLETED,
    /** Handler threw an error; record is cleared so the key can be retried. */
    FAILED
}
