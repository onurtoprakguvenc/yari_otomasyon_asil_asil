package org.example.yari.queue;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.example.yari.model.BacklogTask;
import org.example.yari.model.TaskPriority;
import org.example.yari.model.TaskState;
import org.example.yari.schema.SchemaRegistry;
import org.example.yari.state.TaskStateMachine;
import org.example.yari.util.PromptValidator;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Project-level service managing the ordered backlog of AI prompt tasks.
 * Provides queue manipulation (add, remove, pause, resume) and
 * dependency-aware scheduling.
 */
@Service(Service.Level.PROJECT)
public final class TaskQueueManager {

    private static final Logger LOG = Logger.getInstance(TaskQueueManager.class);

    private final Project project;
    private final List<BacklogTask> tasks = new CopyOnWriteArrayList<>();
    private final List<QueueChangeListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean queuePaused = false;

    public TaskQueueManager(Project project) {
        this.project = project;
    }

    /**
     * Adds a task to the backlog after validating its prompt template and schema reference.
     *
     * @return validation errors, empty if task was added successfully
     */
    public List<String> addTask(BacklogTask task) {
        // Validate prompt template
        List<String> errors = PromptValidator.validate(
                task.getPromptTemplate(), task.getPromptParameters());

        if (!errors.isEmpty()) {
            LOG.warn("Rejected malformed task '" + task.getName() + "': " + errors);
            return errors;
        }

        // Validate schema reference exists
        if (task.getOutputSchemaId() != null && !task.getOutputSchemaId().isBlank()) {
            if (!SchemaRegistry.getInstance().has(task.getOutputSchemaId())) {
                String err = "Output schema '" + task.getOutputSchemaId() + "' not found in registry.";
                LOG.warn(err);
                return List.of(err);
            }
        }

        // Validate dependency references
        for (String depId : task.getDependsOn()) {
            if (getTaskById(depId) == null) {
                String err = "Dependency task '" + depId + "' not found in queue.";
                LOG.warn(err);
                return List.of(err);
            }
        }

        tasks.add(task);
        fireQueueChanged();
        LOG.info("Task added: " + task);
        return List.of();
    }

    /**
     * Returns the next executable task, respecting priority ordering and dependency constraints.
     * A task is eligible if:
     * 1. It is in QUEUED state
     * 2. All its dependencies are in COMPLETED state
     * 3. Its backoff period (if any) has elapsed
     * 4. The queue is not paused
     */
    public Optional<BacklogTask> nextExecutable() {
        if (queuePaused) return Optional.empty();

        long now = System.currentTimeMillis();

        return tasks.stream()
                .filter(t -> t.getState() == TaskState.QUEUED)
                .filter(t -> t.getBackoffUntilEpochMs() <= now)
                .filter(this::allDependenciesCompleted)
                .max(Comparator.comparingInt(t -> t.getPriority().getWeight()));
    }

    /**
     * Returns tasks currently in rate-limit backoff that are ready to retry.
     */
    public List<BacklogTask> getRetryableRateLimited() {
        long now = System.currentTimeMillis();
        return tasks.stream()
                .filter(t -> t.getState() == TaskState.PAUSED_RATE_LIMIT)
                .filter(t -> t.getBackoffUntilEpochMs() <= now)
                .collect(Collectors.toList());
    }

    private boolean allDependenciesCompleted(BacklogTask task) {
        for (String depId : task.getDependsOn()) {
            BacklogTask dep = getTaskById(depId);
            if (dep == null || dep.getState() != TaskState.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    public BacklogTask getTaskById(String id) {
        return tasks.stream().filter(t -> t.getId().equals(id)).findFirst().orElse(null);
    }

    public List<BacklogTask> getAllTasks() {
        return Collections.unmodifiableList(tasks);
    }

    public List<BacklogTask> getTasksByState(TaskState state) {
        return tasks.stream().filter(t -> t.getState() == state).collect(Collectors.toList());
    }

    public void removeTask(String taskId) {
        tasks.removeIf(t -> t.getId().equals(taskId));
        fireQueueChanged();
    }

    public void clearCompleted() {
        tasks.removeIf(t -> t.getState() == TaskState.COMPLETED
                || t.getState() == TaskState.CANCELLED
                || t.getState() == TaskState.EXPIRED);
        fireQueueChanged();
    }

    public void pauseQueue() {
        queuePaused = true;
        LOG.info("Queue paused.");
        fireQueueChanged();
    }

    public void resumeQueue() {
        queuePaused = false;
        LOG.info("Queue resumed.");
        fireQueueChanged();
    }

    public boolean isQueuePaused() {
        return queuePaused;
    }

    public int totalCount() { return tasks.size(); }

    public int activeCount() {
        return (int) tasks.stream()
                .filter(t -> t.getState() == TaskState.RUNNING
                        || t.getState() == TaskState.VALIDATING
                        || t.getState() == TaskState.VALIDATING_OUTPUT)
                .count();
    }

    // --- Listener support ---
    public void addListener(QueueChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(QueueChangeListener listener) {
        listeners.remove(listener);
    }

    private void fireQueueChanged() {
        for (QueueChangeListener l : listeners) {
            try {
                l.onQueueChanged();
            } catch (Exception e) {
                LOG.error("Error in queue listener", e);
            }
        }
    }

    @FunctionalInterface
    public interface QueueChangeListener {
        void onQueueChanged();
    }
}
