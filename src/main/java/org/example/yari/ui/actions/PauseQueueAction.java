package org.example.yari.ui.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.example.yari.queue.TaskQueueManager;
import org.jetbrains.annotations.NotNull;

public class PauseQueueAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        project.getService(TaskQueueManager.class).pauseQueue();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabled(project != null
                && !project.getService(TaskQueueManager.class).isQueuePaused());
    }
}
