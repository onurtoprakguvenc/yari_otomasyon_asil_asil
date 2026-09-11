package org.example.yari.state;

import org.example.yari.model.BacklogTask;
import org.example.yari.model.TaskState;

import java.util.*;

/**
 * Enforces valid state transitions for backlog tasks.
 * All transitions must pass through this class to maintain the audit trail
 * and prevent illegal state jumps.
 */
public final class TaskStateMachine {

    private static final Map<TaskState, Set<TaskState>> VALID_TRANSITIONS;

    static {
        Map<TaskState, Set<TaskState>> t = new EnumMap<>(TaskState.class);
        t.put(TaskState.QUEUED, EnumSet.of(
                TaskState.VALIDATING, TaskState.CANCELLED, TaskState.PAUSED_USER));
        t.put(TaskState.VALIDATING, EnumSet.of(
                TaskState.RUNNING, TaskState.FAILED));
        t.put(TaskState.RUNNING, EnumSet.of(
                TaskState.VALIDATING_OUTPUT, TaskState.AWAITING_APPROVAL,
                TaskState.PAUSED_RATE_LIMIT, TaskState.PAUSED_USER,
                TaskState.FAILED, TaskState.CANCELLED));
        t.put(TaskState.PAUSED_RATE_LIMIT, EnumSet.of(
                TaskState.RUNNING, TaskState.CANCELLED, TaskState.PAUSED_USER));
        t.put(TaskState.PAUSED_USER, EnumSet.of(
                TaskState.QUEUED, TaskState.CANCELLED));
        t.put(TaskState.AWAITING_APPROVAL, EnumSet.of(
                TaskState.RUNNING, TaskState.VALIDATING_OUTPUT,
                TaskState.CANCELLED, TaskState.EXPIRED));
        t.put(TaskState.VALIDATING_OUTPUT, EnumSet.of(
                TaskState.COMPLETED, TaskState.FAILED));
        t.put(TaskState.COMPLETED, EnumSet.noneOf(TaskState.class));
        t.put(TaskState.FAILED, EnumSet.of(TaskState.QUEUED)); // allow retry
        t.put(TaskState.CANCELLED, EnumSet.noneOf(TaskState.class));
        t.put(TaskState.EXPIRED, EnumSet.of(TaskState.QUEUED, TaskState.CANCELLED));
        VALID_TRANSITIONS = Collections.unmodifiableMap(t);
    }

    private TaskStateMachine() {}

    /**
     * Attempts a state transition. Returns true if the transition was valid and applied.
     *
     * @param task   the task to transition
     * @param target the desired target state
     * @param reason human-readable reason for the transition
     * @return true if the transition succeeded
     * @throws IllegalStateException if the transition is not allowed
     */
    public static boolean transition(BacklogTask task, TaskState target, String reason) {
        TaskState current = task.getState();
        Set<TaskState> allowed = VALID_TRANSITIONS.getOrDefault(current, EnumSet.noneOf(TaskState.class));

        if (!allowed.contains(target)) {
            throw new IllegalStateException(String.format(
                    "Invalid state transition for task [%s]: %s → %s (reason: %s). Allowed: %s",
                    task.getId(), current, target, reason, allowed));
        }

        task.recordTransition(current, target, reason);
        task.setState(target);
        return true;
    }

    /**
     * Checks whether a specific transition is allowed without performing it.
     */
    public static boolean canTransition(TaskState from, TaskState to) {
        return VALID_TRANSITIONS.getOrDefault(from, EnumSet.noneOf(TaskState.class)).contains(to);
    }

    /**
     * Returns the set of valid target states from the given state.
     */
    public static Set<TaskState> validTargets(TaskState from) {
        return VALID_TRANSITIONS.getOrDefault(from, EnumSet.noneOf(TaskState.class));
    }
}
