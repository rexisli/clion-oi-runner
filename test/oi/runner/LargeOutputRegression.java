package oi.runner;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** Headless regression test. Run with -ea and the plugin and IDE jars on the classpath. */
public class LargeOutputRegression {
    public static void main(String[] args) throws Exception {
        String prefix = "123456789\n".repeat(100_000);
        String expected = prefix + "A";
        String actual = prefix + "B";
        assert OiCasePanel.display(expected).equals(OiCasePanel.display(actual));
        assert OiJudge.firstMismatchLine(expected, actual) == 100_001;
        assert !OiJudge.sameOutput(expected, actual);
        assert OiJudge.firstMismatchLine(expected, expected + " \r\n\r\n") == 0;
        assert OiJudge.firstMismatchLine("1 2", "1\n2") == 1;
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area = new JTextArea(OiCasePanel.display(actual));
            assert area.getLineCount() <= 257;
            area.setText(OiCasePanel.display("x".repeat(1_000_000)));
            assert area.getDocument().getLength() < 16_450;
        });
        System.out.println("PASS: bounded Swing previews, mismatch line 100001, whitespace and line semantics");
    }
}
