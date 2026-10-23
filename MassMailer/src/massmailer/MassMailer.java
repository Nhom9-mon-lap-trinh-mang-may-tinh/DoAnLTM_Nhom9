package massmailer;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Application entry point. */
public final class MassMailer {
    private MassMailer() { }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
            new frmMailer().setVisible(true);
        });
    }
}
