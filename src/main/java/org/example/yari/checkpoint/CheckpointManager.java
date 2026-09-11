package org.example.yari.checkpoint;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.example.yari.model.BacklogTask;
import org.example.yari.model.TaskState;
import org.example.yari.notification.YariNotificationService;
import org.example.yari.settings.YariSettings;
import org.example.yari.state.TaskStateMachine;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Manages mandatory human-in-the-loop approval checkpoints between sequential task dependencies.
 *
 * <p>Per the behavioral constraints, checkpoint approval is <b>always</b> required between
 * sequential pipeline steps, regardless of any payload configuration flags. This is hardcoded
 * and cannot be overridden.</p>
 *
 * <p>The system is non-blocking: tasks waiting for approval remain in AWAITING_APPROVAL state
 * indefinitely (or until the configured timeout), preserving complete task state for
 * zero-data-loss resume.</p>
 */
@Service(Service.Level.PROJECT)
public final class CheckpointManager {

    private static final Logger LOG = Logger.getInstance(CheckpointManager.class);

    private final Project project;
    private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();
    private final ScheduledExecutorService expiryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "YariCheckpointExpiry");
        t.setDaemon(true);
        return t;
    });

    public CheckpointManager(Project project) {
        this.project = project;
    }

    /**
     * Requests approval for a task. The task is transitioned to AWAITING_APPROVAL state
     * and a timeout is scheduled.
     *
     * @param task     the task needing approval
     * @param callback invoked with true if approved, false if expired
     */
    public void requestApproval(BacklogTask task, ApprovalCallback callback) {
        // Hardcoded mandate: always require approval, overriding any config flags
        TaskStateMachine.transition(task, TaskState.AWAITING_APPROVAL,
                "Mandatory human-in-the-loop checkpoint");

        YariSettings.State settings = YariSettings.getInstance().getState();
        long timeoutMs = settings.approvalTimeoutMinutes * 60_000L;

        PendingApproval pending = new PendingApproval(task, callback, Instant.now());
        pendingApprovals.put(task.getId(), pending);

        // Schedule expiry
        ScheduledFuture<?> expiryFuture = expiryScheduler.schedule(() -> {
            PendingApproval pa = pendingApprovals.remove(task.getId());
            if (pa != null && task.getState() == TaskState.AWAITING_APPROVAL) {
                TaskStateMachine.transition(task, TaskState.EXPIRED, "Approval timeout expired");
                YariNotificationService ns = project.getService(YariNotificationService.class);
                ns.notifyCheckpointExpired(task);
                callback.onDecision(false);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        pending.setExpiryFuture(expiryFuture);

        // Notify user
        if (settings.notifyOnApprovalNeeded) {
            YariNotificationService ns = project.getService(YariNotificationService.class);
            ns.notifyApprovalNeeded(task);
        }

        LOG.info(String.format("Checkpoint approval requested for task [%s] '%s', timeout: %d min",
                task.getId(), task.getName(), settings.approvalTimeoutMinutes));
    }

    /**
     * Approves a pending task checkpoint.
     *
     * @param taskId the ID of the task to approve
     * @return true if the task was found and approved
     */
    public boolean approve(String taskId) {
        PendingApproval pa = pendingApprovals.remove(taskId);
        if (pa == null) return false;

        pa.cancelExpiry();
        BacklogTask task = pa.getTask();

        if (task.getState() == TaskState.AWAITING_APPROVAL) {
            // Transition back to RUNNING or VALIDATING_OUTPUT depending on context
            TaskStateMachine.transition(task, TaskState.VALIDATING_OUTPUT,
                    "Human approval granted");
            pa.getCallback().onDecision(true);
            LOG.info("Task [" + taskId + "] approved by user.");
            return true;
        }
        return false;
    }

    /**
     * Rejects a pending task checkpoint, cancelling the task.
     */
    public boolean reject(String taskId) {
        PendingApproval pa = pendingApprovals.remove(taskId);
        if (pa == null) return false;

        pa.cancelExpiry();
        BacklogTask task = pa.getTask();

        if (task.getState() == TaskState.AWAITING_APPROVAL) {
            TaskStateMachine.transition(task, TaskState.CANCELLED, "Human approval denied");
            pa.getCallback().onDecision(false);
            LOG.info("Task [" + taskId + "] rejected by user.");
            return true;
        }
        return false;
    }

    public boolean hasPending(String taskId) {
        return pendingApprovals.containsKey(taskId);
    }

    public int pendingCount() {
        return pendingApprovals.size();
    }

    public void dispose() {
        expiryScheduler.shutdownNow();
        pendingApprovals.clear();
    }

    /**
     * Callback interface for approval decisions.
     */
    @FunctionalInterface
    public interface ApprovalCallback {
        void onDecision(boolean approved);
    }

    private static class PendingApproval {
        private final BacklogTask task;
        private final ApprovalCallback callback;
        private final Instant requestedAt;
        private ScheduledFuture<?> expiryFuture;

        PendingApproval(BacklogTask task, ApprovalCallback callback, Instant requestedAt) {
            this.task = task;
            this.callback = callback;
            this.requestedAt = requestedAt;
        }

        BacklogTask getTask() { return task; }
        ApprovalCallback getCallback() { return callback; }
        Instant getRequestedAt() { return requestedAt; }

        void setExpiryFuture(ScheduledFuture<?> future) { this.expiryFuture = future; }
        void cancelExpiry() {
            if (expiryFuture != null) expiryFuture.cancel(false);
        }
    }
}
