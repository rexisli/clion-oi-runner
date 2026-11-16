package oi.runner;

import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.FlowLayout;

/** Settings page: compiler options for release, debug and judging, plus the judge compiler path. */
public final class OiSettingsConfigurable implements SearchableConfigurable {

    private JTextField releaseField;
    private JTextField debugField;
    private JTextField judgeField;
    private JTextField compilerField;
    private JPanel panel;

    @Override
    public @NotNull String getId() {
        return "oi.runner.settings";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "OI Runner";
    }

    @Override
    public @Nullable JComponent createComponent() {
        releaseField = new JTextField(48);
        debugField = new JTextField(48);
        judgeField = new JTextField(48);
        compilerField = new JTextField(48);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton defaults = new JButton("Restore Defaults");
        defaults.addActionListener(e -> {
            releaseField.setText(OiSettings.DEFAULT_RELEASE);
            debugField.setText(OiSettings.DEFAULT_DEBUG);
            judgeField.setText(OiSettings.DEFAULT_JUDGE);
            compilerField.setText("");
        });
        footer.add(defaults);

        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Release compiler options:"), releaseField, 1, false)
                .addComponent(new JBLabel("<html><small>Used by <b>OI: Run Current File (Release)</b>. "
                        + "Default: " + OiSettings.DEFAULT_RELEASE + "</small></html>"))
                .addVerticalGap(8)
                .addLabeledComponent(new JBLabel("Debug compiler options:"), debugField, 1, false)
                .addComponent(new JBLabel("<html><small>Used by <b>OI: Run Current File (Debug)</b> and "
                        + "<b>OI: Debug Current File</b>. Default: " + OiSettings.DEFAULT_DEBUG + "</small></html>"))
                .addVerticalGap(8)
                .addLabeledComponent(new JBLabel("Test-case compiler options:"), judgeField, 1, false)
                .addComponent(new JBLabel("<html><small>Used by the <b>OI Cases</b> panel. "
                        + "Default: " + OiSettings.DEFAULT_JUDGE + "</small></html>"))
                .addVerticalGap(8)
                .addLabeledComponent(new JBLabel("Test-case compiler:"), compilerField, 1, false)
                .addComponent(new JBLabel("<html><small>Full path to g++. Leave empty to use the MinGW "
                        + "compiler shipped with CLion.</small></html>"))
                .addComponent(footer, 8)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        OiSettings settings = OiSettings.getInstance();
        if (settings == null || releaseField == null) return false;
        return !releaseField.getText().trim().equals(settings.getReleaseOptions())
                || !debugField.getText().trim().equals(settings.getDebugOptions())
                || !judgeField.getText().trim().equals(settings.getJudgeOptions())
                || !compilerField.getText().trim().equals(settings.getCompilerPath());
    }

    @Override
    public void apply() {
        OiSettings settings = OiSettings.getInstance();
        if (settings == null || releaseField == null) return;
        settings.setReleaseOptions(releaseField.getText());
        settings.setDebugOptions(debugField.getText());
        settings.setJudgeOptions(judgeField.getText());
        settings.setCompilerPath(compilerField.getText());
    }

    @Override
    public void reset() {
        OiSettings settings = OiSettings.getInstance();
        if (settings == null || releaseField == null) return;
        releaseField.setText(settings.getReleaseOptions());
        debugField.setText(settings.getDebugOptions());
        judgeField.setText(settings.getJudgeOptions());
        compilerField.setText(settings.getCompilerPath());
    }

    @Override
    public void disposeUIResources() {
        releaseField = null;
        debugField = null;
        judgeField = null;
        compilerField = null;
        panel = null;
    }
}
