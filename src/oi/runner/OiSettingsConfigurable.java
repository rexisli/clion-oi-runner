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

/** Settings page: review and edit the compiler options used for release and debug builds. */
public final class OiSettingsConfigurable implements SearchableConfigurable {

    private JTextField releaseField;
    private JTextField debugField;
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

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton defaults = new JButton("Restore Defaults");
        defaults.addActionListener(e -> {
            releaseField.setText(OiSettings.DEFAULT_RELEASE);
            debugField.setText(OiSettings.DEFAULT_DEBUG);
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
                .addComponent(footer, 8)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        OiSettings settings = OiSettings.getInstance();
        if (settings == null || releaseField == null || debugField == null) return false;
        return !releaseField.getText().trim().equals(settings.getReleaseOptions())
                || !debugField.getText().trim().equals(settings.getDebugOptions());
    }

    @Override
    public void apply() {
        OiSettings settings = OiSettings.getInstance();
        if (settings == null || releaseField == null || debugField == null) return;
        settings.setReleaseOptions(releaseField.getText());
        settings.setDebugOptions(debugField.getText());
    }

    @Override
    public void reset() {
        OiSettings settings = OiSettings.getInstance();
        if (settings == null || releaseField == null || debugField == null) return;
        releaseField.setText(settings.getReleaseOptions());
        debugField.setText(settings.getDebugOptions());
    }

    @Override
    public void disposeUIResources() {
        releaseField = null;
        debugField = null;
        panel = null;
    }
}
