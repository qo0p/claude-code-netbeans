package dev.claudecode.netbeans.actions;

import dev.claudecode.netbeans.ChatTopComponent;
import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.Timer;
import java.awt.event.ActionListener;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;
import org.openide.util.NbBundle.Messages;

/** Opens the Claude Code chat window (Ctrl+Escape, like VS Code). */
@ActionID(category = "Window", id = "dev.claudecode.netbeans.actions.OpenChatAction")
@ActionRegistration(displayName = "#CTL_OpenChatAction")
@ActionReferences({
    @ActionReference(path = "Menu/Tools", position = 146),
    @ActionReference(path = "Menu/Window", position = 222),
    @ActionReference(path = "Shortcuts", name = "D-ESCAPE")
})
@Messages("CTL_OpenChatAction=Claude Code Chat")
public final class OpenChatAction implements ActionListener {

    @Override
    public void actionPerformed(ActionEvent e) {
        if (EventQueue.getCurrentEvent() instanceof KeyEvent) {
            // Opened with Ctrl+Esc: let the Esc key finish first, otherwise NetBeans
            // treats it as "hide this window" for the chat we just opened.
            Timer t = new Timer(200, ev -> ChatTopComponent.showChat());
            t.setRepeats(false);
            t.start();
        } else {
            ChatTopComponent.showChat();
        }
    }
}
