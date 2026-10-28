package dev.claudecode.netbeans.actions;

import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.Action;
import javax.swing.KeyStroke;
import javax.swing.text.Keymap;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;

/**
 * Makes the "Claude Code Chat" shortcut work wherever the focus is.
 *
 * NetBeans runs global shortcuts only for key presses the focused component
 * didn't handle itself, and the code editor swallows Escape-based keys such
 * as Ctrl+Esc. This dispatcher sees Escape key presses before the editor
 * does and, when NetBeans' keymap assigns that key to the chat action, runs
 * it directly. Other keys, and Escape combinations bound to anything else,
 * are left alone, so a shortcut changed in Tools > Options > Keymap is
 * still respected.
 */
public final class ChatShortcut implements KeyEventDispatcher {

    private static final ChatShortcut INSTANCE = new ChatShortcut();
    private boolean swallowRelease;

    private ChatShortcut() {
    }

    public static void install() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(INSTANCE);
    }

    public static void uninstall() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(INSTANCE);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.VK_ESCAPE) {
            return false;
        }
        if (e.getID() == KeyEvent.KEY_RELEASED && swallowRelease) {
            swallowRelease = false;
            e.consume();
            return true; // don't let the editor or a sliding window act on the release
        }
        if (e.getID() != KeyEvent.KEY_PRESSED || e.getModifiersEx() == 0) {
            return false; // plain Esc keeps its usual meaning everywhere
        }
        Action action = chatActionFor(KeyStroke.getKeyStrokeForEvent(e));
        if (action == null || !action.isEnabled()) {
            return false;
        }
        e.consume();
        swallowRelease = true;
        action.actionPerformed(new ActionEvent(e.getSource(), ActionEvent.ACTION_PERFORMED, "shortcut"));
        return true;
    }

    /** The chat action if the global keymap binds {@code ks} to it, otherwise null. */
    private static Action chatActionFor(KeyStroke ks) {
        Keymap keymap = Lookup.getDefault().lookup(Keymap.class);
        if (keymap == null) {
            return null;
        }
        Action a = keymap.getAction(ks);
        if (a == null) {
            return null;
        }
        Object name = a.getValue(Action.NAME);
        String expected = NbBundle.getMessage(ChatShortcut.class, "CTL_OpenChatAction");
        return name != null && expected.equals(name.toString().replace("&", "")) ? a : null;
    }
}
