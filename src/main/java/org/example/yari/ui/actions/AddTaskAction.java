package org.example.yari.ui.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.example.yari.model.BacklogTask;
import org.example.yari.model.TaskPriority;
import org.example.yari.queue.TaskQueueManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;
import java.util.Map;

/**
 * Action to add a new task to the backlog via a dialog.
 */
public class AddTaskAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        AddTaskDialog dialog = new AddTaskDialog(project);
        if (dialog.showAndGet()) {
            BacklogTask task = new BacklogTask(
                    dialog.getTaskName(),
                    dialog.getPromptTemplate(),
                    Map.of(), // no dynamic parameters in simple dialog
                    dialog.getSelectedPriority(),
                    List.of(),
                    dialog.getSchemaId(),
                    true // always require approval per spec
            );

            TaskQueueManager qm = project.getService(TaskQueueManager.class);
            List<String> errors = qm.addTask(task);
            if (!errors.isEmpty()) {
                Messages.showErrorDialog(project,
                        "Task validation failed:\n" + String.join("\n", errors),
                        "Invalid Task");
            }
        }
    }

    private static class AddTaskDialog extends DialogWrapper {
        private JBTextField nameField;
        private JTextArea promptArea;
        private JComboBox<TaskPriority> priorityCombo;
        private JBTextField schemaIdField;

        protected AddTaskDialog(Project project) {
            super(project, true);
            setTitle("Add Task to Backlog");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            nameField = new JBTextField();
            promptArea = new JTextArea(8, 50);
            promptArea.setLineWrap(true);
            promptArea.setWrapStyleWord(true);
            priorityCombo = new JComboBox<>(TaskPriority.values());
            priorityCombo.setSelectedItem(TaskPriority.NORMAL);
            schemaIdField = new JBTextField();

            return FormBuilder.createFormBuilder()
                    .addLabeledComponent("Task Name:", nameField)
                    .addLabeledComponent("Prompt Template:", new JScrollPane(promptArea))
                    .addLabeledComponent("Priority:", priorityCombo)
                    .addLabeledComponent("Output Schema ID (optional):", schemaIdField)
                    .getPanel();
        }

        String getTaskName() { return nameField.getText().strip(); }
        String getPromptTemplate() { return promptArea.getText(); }
        TaskPriority getSelectedPriority() { return (TaskPriority) priorityCombo.getSelectedItem(); }
        String getSchemaId() {
            String id = schemaIdField.getText().strip();
            return id.isEmpty() ? null : id;
        }
    }
}
