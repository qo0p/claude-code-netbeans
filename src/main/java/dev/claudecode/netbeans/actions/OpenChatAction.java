package dev.claudecode.netbeans.actions;

import dev.claudecode.netbeans.ChatTopComponent;
import java.awt.event.ActionEvent;
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
        ChatTopComponent.showChat();
    }
}
