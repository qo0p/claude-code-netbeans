package dev.claudecode.netbeans.actions;

import dev.claudecode.netbeans.ClaudeCodeService;
import dev.claudecode.netbeans.TerminalLauncher;
import dev.claudecode.netbeans.core.ClaudeIdeServer;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;
import org.openide.util.NbBundle.Messages;

@ActionID(category = "Tools", id = "dev.claudecode.netbeans.actions.StatusAction")
@ActionRegistration(displayName = "#CTL_StatusAction")
@ActionReference(path = "Menu/Tools", position = 154)
@Messages("CTL_StatusAction=Claude Code Status")
public final class StatusAction implements ActionListener {

    @Override
    public void actionPerformed(ActionEvent e) {
        ClaudeIdeServer server = ClaudeCodeService.get().server();
        if (server == null) {
            DialogDisplayer.getDefault().notify(new NotifyDescriptor.Message(
                    "The Claude Code integration is not running.\n" + ClaudeCodeService.get().startupError(),
                    NotifyDescriptor.ERROR_MESSAGE));
            return;
        }
        File dir = StartClaudeAction.workingDirectory();
        String command = TerminalLauncher.manualCommand(dir, server.port());
        int clients = server.connectionCount();
        String message = "Listening on 127.0.0.1:" + server.port() + "\n"
                + "Lock file: " + server.lockFile() + "\n"
                + "Connected Claude Code sessions: " + clients + "\n\n"
                + "To connect from any terminal (including NetBeans' own), run:\n"
                + command + "\n\n"
                + "Or start claude normally inside a project folder and type /ide.";
        String copy = "Copy command";
        NotifyDescriptor nd = new NotifyDescriptor(message, "Claude Code", NotifyDescriptor.DEFAULT_OPTION,
                NotifyDescriptor.INFORMATION_MESSAGE, new Object[]{copy, NotifyDescriptor.CLOSED_OPTION}, copy);
        if (copy.equals(DialogDisplayer.getDefault().notify(nd))) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(command), null);
        }
    }
}
