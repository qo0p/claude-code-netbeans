package dev.claudecode.netbeans;

import dev.claudecode.netbeans.core.ClaudeIdeServer;
import dev.claudecode.netbeans.core.Ide;
import java.awt.EventQueue;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Objects;
import javax.swing.Timer;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.text.JTextComponent;
import org.netbeans.api.editor.EditorRegistry;

/**
 * Follows the focused editor and its caret, and tells Claude Code what is
 * selected (debounced, so dragging a selection doesn't flood the socket).
 */
final class SelectionTracker implements PropertyChangeListener, CaretListener {

    private final ClaudeIdeServer server;
    private final Timer debounce;
    private JTextComponent current;
    private Ide.Selection lastSent;

    SelectionTracker(ClaudeIdeServer server) {
        this.server = server;
        this.debounce = new Timer(200, e -> publish());
        this.debounce.setRepeats(false);
    }

    void install() {
        EventQueue.invokeLater(() -> {
            EditorRegistry.addPropertyChangeListener(this);
            attach(EditorRegistry.lastFocusedComponent());
        });
    }

    void uninstall() {
        EventQueue.invokeLater(() -> {
            EditorRegistry.removePropertyChangeListener(this);
            attach(null);
            debounce.stop();
        });
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        if (!EventQueue.isDispatchThread()) {
            EventQueue.invokeLater(() -> propertyChange(evt));
            return;
        }
        attach(EditorRegistry.lastFocusedComponent());
        debounce.restart();
    }

    @Override
    public void caretUpdate(CaretEvent e) {
        debounce.restart();
    }

    private void attach(JTextComponent c) {
        if (c == current) {
            return;
        }
        if (current != null) {
            current.removeCaretListener(this);
        }
        current = c;
        if (c != null) {
            c.addCaretListener(this);
        }
    }

    private void publish() {
        Ide.Selection s = EditorSelection.from(current);
        if (s != null && !Objects.equals(s, lastSent)) {
            lastSent = s;
            server.publishSelection(s);
        }
    }
}
