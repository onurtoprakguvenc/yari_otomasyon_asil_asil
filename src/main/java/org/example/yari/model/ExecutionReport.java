package org.example.yari.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Consolidated execution report aggregating pipeline results.
 */
public class ExecutionReport {
    private final String reportId;
    private final Instant generatedAt;
    private final List<TaskSummary> taskSummaries;
    private int totalTasks;
    private int completedTasks;
    private int failedTasks;
    private int pendingTasks;

    public ExecutionReport(String reportId) {
        this.reportId = reportId;
        this.generatedAt = Instant.now();
        this.taskSummaries = new ArrayList<>();
    }

    public void addTaskSummary(TaskSummary summary) {
        taskSummaries.add(summary);
        recalculate();
    }

    private void recalculate() {
        totalTasks = taskSummaries.size();
        completedTasks = (int) taskSummaries.stream().filter(s -> s.finalState == TaskState.COMPLETED).count();
        failedTasks = (int) taskSummaries.stream().filter(s -> s.finalState == TaskState.FAILED).count();
        pendingTasks = totalTasks - completedTasks - failedTasks;
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Yarı Otomasyon Execution Report\n\n");
        sb.append(String.format("**Report ID:** %s  \n", reportId));
        sb.append(String.format("**Generated:** %s  \n\n", generatedAt));
        sb.append("## Summary\n\n");
        sb.append(String.format("| Metric | Count |\n|--------|-------|\n"));
        sb.append(String.format("| Total | %d |\n", totalTasks));
        sb.append(String.format("| Completed | %d |\n", completedTasks));
        sb.append(String.format("| Failed | %d |\n", failedTasks));
        sb.append(String.format("| Pending | %d |\n\n", pendingTasks));
        sb.append("## Task Details\n\n");
        for (TaskSummary ts : taskSummaries) {
            sb.append(String.format("### %s (`%s`)\n\n", ts.name, ts.taskId));
            sb.append(String.format("- **State:** %s\n", ts.finalState));
            sb.append(String.format("- **Priority:** %s\n", ts.priority));
            sb.append(String.format("- **Retries:** %d\n", ts.retryCount));
            if (ts.errorMessage != null) {
                sb.append(String.format("- **Error:** %s\n", ts.errorMessage));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // --- Getters ---
    public String getReportId() { return reportId; }
    public Instant getGeneratedAt() { return generatedAt; }
    public List<TaskSummary> getTaskSummaries() { return taskSummaries; }
    public int getTotalTasks() { return totalTasks; }
    public int getCompletedTasks() { return completedTasks; }
    public int getFailedTasks() { return failedTasks; }
    public int getPendingTasks() { return pendingTasks; }

    public static class TaskSummary {
        public final String taskId;
        public final String name;
        public final TaskState finalState;
        public final TaskPriority priority;
        public final int retryCount;
        public final String errorMessage;

        public TaskSummary(BacklogTask task) {
            this.taskId = task.getId();
            this.name = task.getName();
            this.finalState = task.getState();
            this.priority = task.getPriority();
            this.retryCount = task.getRetryCount();
            this.errorMessage = task.getErrorMessage();
        }
    }
}
