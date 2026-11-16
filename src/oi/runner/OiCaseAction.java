package oi.runner;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/** Opens (and focuses) the test-case panel. */
public final class OiCaseAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("OI Cases");
        if (window != null) window.activate(null, true);
    }
}
