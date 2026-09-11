package org.example.yari.executor;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.example.yari.checkpoint.CheckpointManager;
import org.example.yari.model.BacklogTask;
import org.example.yari.model.ExecutionReport;
import org.example.yari.model.OutputSchemaTemplate;
import org.example.yari.model.TaskState;
import org.example.yari.notification.YariNotificationService;
import org.example.yari.queue.TaskQueueManager;
import org.example.yari.schema.SchemaRegistry;
import org.example.yari.schema.SchemaValidator;
import org.example.yari.settings.YariSettings;
import org.example.yari.state.TaskStateMachine;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Core execution engine that processes the backlog queue asynchronously.
 *
 * <p>Runs a polling loop on a background thread, picking the next eligible task
 * from the queue and executing it through the full pipeline:
 * validate prompt → execute API → checkpoint approval → validate output → complete.</p>
 *
 * <p>Handles rate limiting with exponential backoff, enforces mandatory
 * human-in-the-loop checkpoints, and filters all outputs through strict schemas.</p>
 */
@Service(Service.Level.PROJECT)
public final class BacklogExecutorService {

    private static final Logger LOG = Logger.getInstance(BacklogExecutorService.class);
    private static final long POLL_INTERVAL_MS = 3000;

    private final Project project;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private ScheduledFuture<?> pollFuture;

    public BacklogExecutorService(Project project) {
        this.project = project;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "YariExecutor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the background execution loop.
     */
    public synchronized void start() {
        if (running) return;
        running = true;
        pollFuture = scheduler.scheduleWithFixedDelay(
                this::pollAndExecute, 1000, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        LOG.info("Backlog executor started.");
    }

    /**
     * Stops the background execution loop.
     */
    public synchronized void stop() {
        running = false;
        if (pollFuture != null) {
            pollFuture.cancel(false);
            pollFuture = null;
        }
        LOG.info("Backlog executor stopped.");
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Main polling loop body. Picks the next eligible task and executes it.
     */
    private void pollAndExecute() {
        if (!running) return;

        try {
            TaskQueueManager queueManager = project.getService(TaskQueueManager.class);
            YariSettings.State settings = YariSettings.getInstance().getState();

            // Re-queue rate-limited tasks whose backoff has elapsed
            for (BacklogTask rateLimited : queueManager.getRetryableRateLimited()) {
                TaskStateMachine.transition(rateLimited, TaskState.RUNNING,
                        "Retrying after rate-limit backoff");
                executeTask(rateLimited);
                return; // one task per poll cycle
            }

            // Check concurrent limit
            if (queueManager.activeCount() >= settings.maxConcurrentTasks) {
                return;
            }

            // Pick next task
            Optional<BacklogTask> next = queueManager.nextExecutable();
            if (next.isEmpty()) return;

            BacklogTask task = next.get();
            executeTask(task);

        } catch (Exception e) {
            LOG.error("Error in executor poll loop", e);
        }
    }

    /**
     * Executes a single task through the full pipeline.
     */
    private void executeTask(BacklogTask task) {
        YariNotificationService notifications = project.getService(YariNotificationService.class);
        YariSettings.State settings = YariSettings.getInstance().getState();

        try {
            // Step 1: Validate prompt
            if (task.getState() == TaskState.QUEUED) {
                TaskStateMachine.transition(task, TaskState.VALIDATING, "Validating prompt template");
            }

            String resolvedPrompt = task.resolvePrompt();

            // Step 2: Transition to RUNNING and call the provider
            if (task.getState() == TaskState.VALIDATING) {
                TaskStateMachine.transition(task, TaskState.RUNNING, "Prompt validated, executing");
            }

            AiProvider provider = ProviderFactory.create();
            String rawOutput = provider.execute(resolvedPrompt, settings.modelId);
            task.setRawOutput(rawOutput);

            // Step 3: Mandatory human-in-the-loop checkpoint
            // Per spec: "Override any payload checkpoint configuration flags with hardcoded
            // system directives requiring manual user approval between sequential pipeline steps."
            CheckpointManager checkpointMgr = project.getService(CheckpointManager.class);
            checkpointMgr.requestApproval(task, approved -> {
                if (approved) {
                    completeValidation(task);
                } else {
                    LOG.info("Task [" + task.getId() + "] not approved, halted.");
                }
            });

        } catch (AiProvider.RateLimitException e) {
            handleRateLimit(task, e, settings);
            notifications.notifyRateLimited(task, task.getBackoffUntilEpochMs() - System.currentTimeMillis());

        } catch (AiProvider.ProviderException e) {
            handleProviderError(task, e, settings);

        } catch (IllegalStateException e) {
            LOG.error("State machine error for task [" + task.getId() + "]", e);
            task.setErrorMessage("State error: " + e.getMessage());
            try {
                TaskStateMachine.transition(task, TaskState.FAILED, e.getMessage());
                notifications.notifyTaskFailed(task);
            } catch (IllegalStateException ignored) {}

        } catch (Exception e) {
            LOG.error("Unexpected error executing task [" + task.getId() + "]", e);
            task.setErrorMessage("Unexpected error: " + e.getMessage());
            try {
                TaskStateMachine.transition(task, TaskState.FAILED, e.getMessage());
                notifications.notifyTaskFailed(task);
            } catch (IllegalStateException ignored) {}
        }
    }

    /**
     * Completes the output validation step after approval is granted.
     */
    private void completeValidation(BacklogTask task) {
        YariNotificationService notifications = project.getService(YariNotificationService.class);

        try {
            // Step 4: Validate output against schema
            if (task.getOutputSchemaId() != null && !task.getOutputSchemaId().isBlank()) {
                Optional<OutputSchemaTemplate> schemaOpt =
                        SchemaRegistry.getInstance().get(task.getOutputSchemaId());

                if (schemaOpt.isEmpty()) {
                    // Schema definitions omitted — halt immediately per spec
                    task.setErrorMessage("Schema '" + task.getOutputSchemaId()
                            + "' not found. Pipeline halted: schema definitions must not be omitted.");
                    TaskStateMachine.transition(task, TaskState.FAILED,
                            "Missing schema definition, pipeline halted");
                    notifications.notifySchemaViolation(task, task.getErrorMessage());
                    return;
                }

                SchemaValidator.ValidationResult result =
                        SchemaValidator.validate(task.getRawOutput(), schemaOpt.get());

                if (!result.isValid()) {
                    // Schema mismatch: isolate failure, prevent downstream execution
                    task.setErrorMessage("Schema validation failed: " + result.getErrorSummary());
                    TaskStateMachine.transition(task, TaskState.FAILED,
                            "Output schema mismatch: " + result.getErrorSummary());
                    notifications.notifySchemaViolation(task, result.getErrorSummary());
                    return;
                }

                task.setValidatedOutput(result.getCleanedOutput());
            } else {
                // No schema required; use raw output
                task.setValidatedOutput(task.getRawOutput());
            }

            // Step 5: Complete
            task.setCompletedAt(Instant.now());
            TaskStateMachine.transition(task, TaskState.COMPLETED, "Task completed successfully");
            notifications.notifyTaskCompleted(task);

            LOG.info("Task [" + task.getId() + "] completed successfully.");

        } catch (Exception e) {
            LOG.error("Error in validation completion for task [" + task.getId() + "]", e);
            task.setErrorMessage("Validation error: " + e.getMessage());
            try {
                TaskStateMachine.transition(task, TaskState.FAILED, e.getMessage());
                notifications.notifyTaskFailed(task);
            } catch (IllegalStateException ignored) {}
        }
    }

    /**
     * Handles rate-limit errors with exponential backoff.
     */
    private void handleRateLimit(BacklogTask task, AiProvider.RateLimitException e,
                                 YariSettings.State settings) {
        int retries = task.getRetryCount();

        // Calculate exponential backoff
        long backoff = Math.min(
                (long) (settings.initialBackoffMs * Math.pow(settings.backoffMultiplier, retries)),
                settings.maxBackoffMs);

        // Use provider's retry-after if larger
        backoff = Math.max(backoff, e.getRetryAfterMs());

        task.setRetryCount(retries + 1);
        task.setBackoffUntilEpochMs(System.currentTimeMillis() + backoff);

        try {
            TaskStateMachine.transition(task, TaskState.PAUSED_RATE_LIMIT,
                    String.format("Rate limited (attempt %d), backoff %d ms", retries + 1, backoff));
        } catch (IllegalStateException ex) {
            LOG.error("Cannot transition to PAUSED_RATE_LIMIT", ex);
        }

        LOG.warn(String.format("Task [%s] rate limited, retry #%d in %d ms",
                task.getId(), retries + 1, backoff));
    }

    /**
     * Handles non-rate-limit provider errors with retry logic.
     */
    private void handleProviderError(BacklogTask task, AiProvider.ProviderException e,
                                     YariSettings.State settings) {
        YariNotificationService notifications = project.getService(YariNotificationService.class);
        int retries = task.getRetryCount();

        if (retries < settings.maxRetriesPerTask) {
            task.setRetryCount(retries + 1);
            long backoff = (long) (settings.initialBackoffMs * Math.pow(settings.backoffMultiplier, retries));
            task.setBackoffUntilEpochMs(System.currentTimeMillis() + backoff);

            try {
                TaskStateMachine.transition(task, TaskState.PAUSED_RATE_LIMIT,
                        String.format("API error (attempt %d/%d): %s",
                                retries + 1, settings.maxRetriesPerTask, e.getMessage()));
            } catch (IllegalStateException ex) {
                LOG.error("Cannot transition for retry", ex);
            }
        } else {
            task.setErrorMessage("Max retries exceeded: " + e.getMessage());
            try {
                TaskStateMachine.transition(task, TaskState.FAILED,
                        "Max retries exceeded: " + e.getMessage());
                notifications.notifyTaskFailed(task);
            } catch (IllegalStateException ex) {
                LOG.error("Cannot transition to FAILED", ex);
            }
        }
    }

    /**
     * Generates a consolidated execution report for all current tasks.
     */
    public ExecutionReport generateReport() {
        TaskQueueManager queueManager = project.getService(TaskQueueManager.class);
        ExecutionReport report = new ExecutionReport(UUID.randomUUID().toString().substring(0, 8));
        for (BacklogTask task : queueManager.getAllTasks()) {
            report.addTaskSummary(new ExecutionReport.TaskSummary(task));
        }
        return report;
    }

    public void dispose() {
        stop();
        scheduler.shutdownNow();
    }
}
