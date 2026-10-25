package oi.runner;

import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Runs or debugs the C/C++ source file the caret is in with one of the two compiler-option sets,
 * and lets {@link OiStats} append wall time / CPU time / peak memory once the process exits.
 */
public abstract class OiFileAction extends DumbAwareAction {

    /** Executor id from {@code com.intellij.execution.executors}. */
    protected abstract String executorId();

    /** Which compiler-option set this action builds with. */
    protected abstract Oi.Build build();

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        VirtualFile file = Oi.sourceFileOf(e.getData(CommonDataKeys.EDITOR), e.getData(CommonDataKeys.VIRTUAL_FILE));
        if (!Oi.isCppSource(file)) {
            Oi.notify(project, "No C/C++ file in focus",
                    "Put the caret in a C or C++ source file, then run this action again.");
            return;
        }
        RunnerAndConfigurationSettings settings = Oi.settingsFor(project, file, build());
        if (settings == null) return;
        Oi.execute(project, settings, executorId());
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile file = Oi.sourceFileOf(editor, e.getData(CommonDataKeys.VIRTUAL_FILE));
        e.getPresentation().setEnabledAndVisible(Oi.isCppSource(file));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
