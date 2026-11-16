package oi.runner;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Startup behaviour of the "OI Cases" tool window: every project opens with it collapsed, and while
 * the indexer is still running the stripe button stays greyed out. Opening the panel mid-index would
 * build three editors per case against cold caches, which is exactly the cost the panel defers.
 */
public final class OiCaseStartup implements ProjectActivity {

    private static final String TOOL_WINDOW_ID = "OI Cases";

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        // The tool window exists only after the frame is laid out; run once that has happened.
        ApplicationManager.getApplication().invokeLater(() -> prepare(project));
        return Unit.INSTANCE;
    }

    private static void prepare(@NotNull Project project) {
        if (project.isDisposed()) return;
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (window == null) return;
        window.hide();
        DumbService dumb = DumbService.getInstance(project);
        if (!dumb.isDumb()) return;
        window.setAvailable(false, null);
        dumb.runWhenSmart(() -> {
            if (!project.isDisposed()) window.setAvailable(true, null);
        });
    }
}
