package org.example.yari.model;

/**
 * Finite state machine states for backlog tasks.
 * Transitions are enforced by {@link org.example.yari.state.TaskStateMachine}.
 */
public enum TaskState {
    /** Queued but not yet started. */
    QUEUED,

    /** Prompt template is being validated before execution. */
    VALIDATING,

    /** Actively being executed against the provider API. */
    RUNNING,

    /** Paused due to API rate-limit exhaustion; exponential backoff active. */
    PAUSED_RATE_LIMIT,

    /** Paused by the user manually. */
    PAUSED_USER,

    /** Waiting for mandatory human-in-the-loop approval to continue. */
    AWAITING_APPROVAL,

    /** Output is being validated against the output schema. */
    VALIDATING_OUTPUT,

    /** Task completed successfully; output is schema-compliant. */
    COMPLETED,

    /** Task failed due to validation, API, or schema errors. */
    FAILED,

    /** Task was cancelled by the user. */
    CANCELLED,

    /** Checkpoint approval expired without a response. */
    EXPIRED
}
