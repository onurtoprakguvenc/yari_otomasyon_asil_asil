package org.example.yari.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for the Yarı Otomasyon tool window.
 */
public class YariToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        YariToolWindowPanel panel = new YariToolWindowPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "Backlog", false);
        toolWindow.getContentManager().addContent(content);
    }
}
