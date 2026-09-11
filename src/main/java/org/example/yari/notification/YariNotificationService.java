package org.example.yari.notification;

import com.intellij.notification.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.example.yari.model.BacklogTask;
import org.example.yari.model.TaskState;

/**
 * Centralized notification service for the Yarı Otomasyon plugin.
 * Emits notifications to the IDE notification bus for state changes,
 * approval requests, errors, and rate-limit events.
 */
@Service(Service.Level.PROJECT)
public final class YariNotificationService {

    private static final String GROUP_ID = "Yarı Otomasyon";
    private static final NotificationGroup NOTIFICATION_GROUP =
            NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID);

    private final Project project;

    public YariNotificationService(Project project) {
        this.project = project;
    }

    public void notifyApprovalNeeded(BacklogTask task) {
        Notification notification = NOTIFICATION_GROUP.createNotification(
                "Approval Required",
                String.format("Task '%s' (%s) is awaiting your approval to continue.",
                        task.getName(), task.getId()),
                NotificationType.WARNING
        );
        notification.notify(project);
    }

    public void notifyTaskCompleted(BacklogTask task) {
        Notification notification = NOTIFICATION_GROUP.createNotification(
                "Task Completed",
                String.format("Task '%s' (%s) completed successfully.", task.getName(), task.getId()),
                NotificationType.INFORMATION
        );
        notification.notify(project);
    }

    public void notifyTaskFailed(BacklogTask task) {
        Notification notification = NOTIFICATION_GROUP.createNotification(
                "Task Failed",
                String.format("Task '%s' (%s) failed: %s",
                        task.getName(), task.getId(),
                        task.getErrorMessage() != null ? task.getErrorMessage() : "Unknown error"),
                NotificationType.ERROR
        );
        notification.notify(project);
    }

    public void notifyRateLimited(BacklogTask task, long backoffMs) {
        Notification notification = NOTIFICATION_GROUP.createNotification(
                "Rate Limited",
                String.format("Task '%s' paused due to rate limiting. Retrying in %d seconds.",
                        task.getName(), backoffMs / 1000),
                NotificationType.WARNING
        );
        notification.notify(project);
    }

    public void notifyCheckpointExpired(BacklogTask task) {
        Notification notification = NOTIFICATION_GROUP.createNotification(
                "Checkpoint Expired",
                String.format("Approval for task '%s' (%s) expired. Task moved to EXPIRED state.",
                        task.getName(), task.getId()),
                NotificationType.WARNING
        );
        notification.notify(project);
    }

    public void notifySchemaViolation(BacklogTask task, String details) {
        Notification notification = NOTIFICATION_GROUP.createNotification(
                "Schema Validation Failed",
                String.format("Task '%s' output failed schema validation: %s", task.getName(), details),
                NotificationType.ERROR
        );
        notification.notify(project);
    }

    public void notifyInfo(String title, String message) {
        NOTIFICATION_GROUP.createNotification(title, message, NotificationType.INFORMATION).notify(project);
    }

    public void notifyError(String title, String message) {
        NOTIFICATION_GROUP.createNotification(title, message, NotificationType.ERROR).notify(project);
    }
}
