package org.example.yari.model;

import com.google.gson.annotations.SerializedName;

import java.time.Instant;
import java.util.*;

/**
 * Represents a single queued AI prompt task with full state tracking.
 * Immutable fields are set at creation; mutable state is updated by the state machine.
 */
public class BacklogTask {
    private final String id;
    private final String name;
    private String promptTemplate;
    private final Map<String, String> promptParameters;
    private final TaskPriority priority;
    private final List<String> dependsOn;
    private final String outputSchemaId;
    private final boolean requiresApproval;

    // Mutable execution state
    private TaskState state;
    private String rawOutput;
    private String validatedOutput;
    private String errorMessage;
    private int retryCount;
    private long backoffUntilEpochMs;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
    private final List<StateTransitionRecord> stateHistory;

    public BacklogTask(String name, String promptTemplate, Map<String, String> promptParameters,
                       TaskPriority priority, List<String> dependsOn, String outputSchemaId,
                       boolean requiresApproval) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.name = name;
        this.promptTemplate = promptTemplate;
        this.promptParameters = promptParameters != null ? new HashMap<>(promptParameters) : new HashMap<>();
        this.priority = priority;
        this.dependsOn = dependsOn != null ? new ArrayList<>(dependsOn) : List.of();
        this.outputSchemaId = outputSchemaId;
        this.requiresApproval = requiresApproval;
        this.state = TaskState.QUEUED;
        this.retryCount = 0;
        this.backoffUntilEpochMs = 0;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.stateHistory = new ArrayList<>();
        recordTransition(null, TaskState.QUEUED, "Task created");
    }

    public void recordTransition(TaskState from, TaskState to, String reason) {
        stateHistory.add(new StateTransitionRecord(from, to, reason, Instant.now()));
        this.updatedAt = Instant.now();
    }

    // --- Dynamic Parameter Injection ---
    public void putParameter(String key, String value) {
        if (key != null && value != null) {
            this.promptParameters.put(key, value);
        }
    }

    // --- Setters for mutable fields ---
    public void setPromptTemplate(String promptTemplate) {
        this.promptTemplate = promptTemplate;
        this.updatedAt = Instant.now();
    }

    // --- Getters ---
    public String getId() { return id; }
    public String getName() { return name; }
    public String getPromptTemplate() { return promptTemplate; }
    public Map<String, String> getPromptParameters() { return Collections.unmodifiableMap(promptParameters); }
    public TaskPriority getPriority() { return priority; }
    public List<String> getDependsOn() { return Collections.unmodifiableList(dependsOn); }
    public String getOutputSchemaId() { return outputSchemaId; }
    public boolean isRequiresApproval() { return requiresApproval; }
    public TaskState getState() { return state; }
    public String getRawOutput() { return rawOutput; }
    public String getValidatedOutput() { return validatedOutput; }
    public String getErrorMessage() { return errorMessage; }
    public int getRetryCount() { return retryCount; }
    public long getBackoffUntilEpochMs() { return backoffUntilEpochMs; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public List<StateTransitionRecord> getStateHistory() { return Collections.unmodifiableList(stateHistory); }

    // --- Setters for mutable state ---
    public void setState(TaskState state) { this.state = state; }
    public void setRawOutput(String rawOutput) { this.rawOutput = rawOutput; }
    public void setValidatedOutput(String validatedOutput) { this.validatedOutput = validatedOutput; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public void setBackoffUntilEpochMs(long backoffUntilEpochMs) { this.backoffUntilEpochMs = backoffUntilEpochMs; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    /**
     * Resolves the prompt template by interpolating parameters without corrupting technical syntax.
     */
    public String resolvePrompt() {
        String resolved = promptTemplate;
        for (Map.Entry<String, String> entry : promptParameters.entrySet()) {
            resolved = resolved.replace("{{" + entry.getKey() + "}}", entry.getValue() != null ? entry.getValue() : "");
        }
        return resolved;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s (%s) - %s", id, name, priority, state);
    }

    /**
     * Immutable record of a single state transition for the audit trail.
     */
    public static class StateTransitionRecord {
        private final TaskState from;
        private final TaskState to;
        private final String reason;
        private final Instant timestamp;

        public StateTransitionRecord(TaskState from, TaskState to, String reason, Instant timestamp) {
            this.from = from;
            this.to = to;
            this.reason = reason;
            this.timestamp = timestamp;
        }

        public TaskState getFrom() { return from; }
        public TaskState getTo() { return to; }
        public String getReason() { return reason; }
        public Instant getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return String.format("%s: %s → %s (%s)", timestamp, from, to, reason);
        }
    }
}