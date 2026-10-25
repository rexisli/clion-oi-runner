package oi.runner;

import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Locale;

/** Shared plumbing: find / create the single-file run configuration for a file, then execute it. */
public final class Oi {

    /** Configuration type id of CLion's own "C++ file" run configuration. */
    static final String TYPE_ID = "CppFileRunConfiguration";

    /** The two compiler-option sets the plugin keeps apart. */
    enum Build {
        /** Optimised, used for timing runs. */
        RELEASE("release"),
        /** Unoptimised with symbols, used for stepping. */
        DEBUG("debug");

        final String suffix;

        Build(String suffix) {
            this.suffix = suffix;
        }
    }

    private Oi() {
    }

    // ---------------------------------------------------------------- files

    @Nullable
    static VirtualFile sourceFileOf(@Nullable Editor editor, @Nullable VirtualFile contextFile) {
        if (editor != null) {
            VirtualFile f = FileDocumentManager.getInstance().getFile(editor.getDocument());
            if (f != null) return f;
        }
        return contextFile;
    }

    static boolean isCppSource(@Nullable VirtualFile file) {
        if (file == null || file.isDirectory()) return false;
        String ext = file.getExtension();
        if (ext == null) return false;
        switch (ext.toLowerCase(Locale.ROOT)) {
            case "c":
            case "cc":
            case "cpp":
            case "cxx":
            case "c++":
            case "cp":
                return true;
            default:
                return false;
        }
    }

    // -------------------------------------------------------- configuration

    static String optionsOf(Build build) {
        OiSettings settings = OiSettings.getInstance();
        if (settings == null) {
            return build == Build.RELEASE ? OiSettings.DEFAULT_RELEASE : OiSettings.DEFAULT_DEBUG;
        }
        return build == Build.RELEASE ? settings.getReleaseOptions() : settings.getDebugOptions();
    }

    /**
     * Returns the run configuration bound to {@code path} for one compiler-option set. The plugin
     * names its own configurations "{@code <file> [release]}" / "{@code <file> [debug]}" and keeps
     * their compiler options in sync with the settings page, so the two sets never mix.
     */
    @Nullable
    static RunnerAndConfigurationSettings settingsFor(Project project, VirtualFile file, Build build) {
        ConfigurationType type = ConfigurationTypeUtil.findConfigurationType(TYPE_ID);
        if (type == null) {
            notify(project, "CLion's single-file configuration type (" + TYPE_ID + ") is not available.",
                    "This plugin needs CLion's built-in “C++ file” run configuration.");
            return null;
        }
        String path = normalize(file.getPath());
        String name = file.getName() + " [" + build.suffix + "]";
        String options = optionsOf(build);
        RunManager rm = RunManager.getInstance(project);

        for (RunnerAndConfigurationSettings s : rm.getAllSettings()) {
            if (s.getType() == null || !TYPE_ID.equals(s.getType().getId())) continue;
            if (!name.equals(s.getName())) continue;
            if (!path.equalsIgnoreCase(normalize(getSourceFile(s.getConfiguration())))) continue;
            applyCompilerOptions(s.getConfiguration(), options);
            return s;
        }

        ConfigurationFactory[] factories = type.getConfigurationFactories();
        if (factories == null || factories.length == 0) {
            notify(project, "No factory for " + type.getDisplayName() + ".", "");
            return null;
        }
        RunnerAndConfigurationSettings created = rm.createConfiguration(name, factories[0]);
        if (!setSourceFile(created.getConfiguration(), file.getPath())) {
            notify(project, "Could not bind the configuration to " + file.getName() + ".", "");
            rm.removeConfiguration(created);
            return null;
        }
        applyCompilerOptions(created.getConfiguration(), options);
        created.setTemporary(true);
        rm.addConfiguration(created);
        return created;
    }

    static void execute(Project project, RunnerAndConfigurationSettings settings, String executorId) {
        OiStats.hook(project);
        Executor executor = ExecutorRegistry.getInstance().getExecutorById(executorId);
        if (executor == null) {
            notify(project, "Executor " + executorId + " is not available.", "");
            return;
        }
        RunManager.getInstance(project).setSelectedConfiguration(settings);
        ProgramRunnerUtil.executeConfiguration(settings, executor);
    }

    // ------------------------------------------------------- reflection glue
    // The plugin deliberately avoids a compile-time dependency on CLion classes:
    // everything CLion-specific is reached by name at runtime.

    static Object optionsOf(Object configuration) {
        try {
            Method m = method(configuration.getClass(), "getOptions", 0);
            return m == null ? null : m.invoke(configuration);
        } catch (Throwable t) {
            return null;
        }
    }

    static String getSourceFile(Object configuration) {
        Object opts = optionsOf(configuration);
        if (opts == null) return null;
        try {
            Method m = method(opts.getClass(), "getSourceFile", 0);
            Object v = m == null ? null : m.invoke(opts);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean setSourceFile(Object configuration, String path) {
        return setStringOption(configuration, "setSourceFile", path);
    }

    static boolean applyCompilerOptions(Object configuration, String options) {
        return setStringOption(configuration, "setCompilerOptions", options == null ? "" : options);
    }

    static String getCompilerOptions(Object configuration) {
        Object opts = optionsOf(configuration);
        if (opts == null) return "";
        try {
            Method m = method(opts.getClass(), "getCompilerOptions", 0);
            Object v = m == null ? null : m.invoke(opts);
            return v instanceof String ? (String) v : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean setStringOption(Object configuration, String setter, String value) {
        Object opts = optionsOf(configuration);
        if (opts == null) return false;
        try {
            Method m = method(opts.getClass(), setter, 1);
            if (m == null) return false;
            m.invoke(opts, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static Method method(Class<?> start, String name, int argc) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argc) {
                    try {
                        m.setAccessible(true);
                    } catch (Throwable ignored) {
                        // best effort: public members stay reachable
                    }
                    return m;
                }
            }
        }
        return null;
    }

    static String normalize(@Nullable String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    static String fixed(double v, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", v);
    }

    static String humanBytes(long bytes) {
        if (bytes >= 1024L * 1024L) return fixed(bytes / 1048576.0, 2) + " MB";
        return (bytes / 1024L) + " KB";
    }

    static void notify(Project project, String title, String detail) {
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("OI Runner")
                    .createNotification(title, detail, NotificationType.WARNING)
                    .notify(project);
        } catch (Throwable t) {
            com.intellij.openapi.diagnostic.Logger.getInstance("OiRunner").warn(title, t);
        }
    }
}
