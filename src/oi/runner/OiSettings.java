package oi.runner;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Application-wide compiler options, kept in two separate sets: one for release builds,
 * one for debug builds. CLion's single-file run configuration has a single compiler-options
 * field, so splitting the two is exactly what this plugin adds.
 */
@State(name = "OiRunnerSettings", storages = @Storage("oi-runner.xml"))
public final class OiSettings implements PersistentStateComponent<OiSettings.State> {

    /** -O2 without asserts: what a judge machine is likely to run. */
    public static final String DEFAULT_RELEASE = "-O2 -DNDEBUG";
    /** Symbols, no optimisation: what you want for stepping and for comparing against release. */
    public static final String DEFAULT_DEBUG = "-O0 -g";

    /** Persisted payload. */
    public static final class State {
        public String releaseOptions = DEFAULT_RELEASE;
        public String debugOptions = DEFAULT_DEBUG;
    }

    private State state = new State();

    @Nullable
    public static OiSettings getInstance() {
        return ApplicationManager.getApplication().getService(OiSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state == null ? new State() : state;
    }

    public String getReleaseOptions() {
        return state.releaseOptions == null ? DEFAULT_RELEASE : state.releaseOptions.trim();
    }

    public void setReleaseOptions(String value) {
        state.releaseOptions = value == null ? "" : value.trim();
    }

    public String getDebugOptions() {
        return state.debugOptions == null ? DEFAULT_DEBUG : state.debugOptions.trim();
    }

    public void setDebugOptions(String value) {
        state.debugOptions = value == null ? "" : value.trim();
    }

    public void resetToDefaults() {
        state = new State();
    }
}
