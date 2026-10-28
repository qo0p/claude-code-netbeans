package dev.claudecode.netbeans;

import dev.claudecode.netbeans.core.ClaudeIdeServer;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.modules.OnStart;
import org.openide.modules.OnStop;
import org.openide.util.RequestProcessor;

/** Owns the Claude Code integration server for the lifetime of the IDE. */
public final class ClaudeCodeService {

    private static final Logger LOG = Logger.getLogger(ClaudeCodeService.class.getName());
    private static final ClaudeCodeService INSTANCE = new ClaudeCodeService();

    private ClaudeIdeServer server;
    private SelectionTracker tracker;
    private PropertyChangeListener projectsListener;
    private String startupError;

    private ClaudeCodeService() {
    }

    public static ClaudeCodeService get() {
        return INSTANCE;
    }

    /** The running server, or null if it could not be started. */
    public synchronized ClaudeIdeServer server() {
        return server;
    }

    public synchronized String startupError() {
        return startupError;
    }

    synchronized void start() {
        if (server != null) {
            return;
        }
        LOG.info("Claude Code for NetBeans " + dev.claudecode.netbeans.core.PluginVersion.longText());
        ClaudeIdeServer s = new ClaudeIdeServer("NetBeans", new NetBeansIde());
        try {
            s.start();
        } catch (IOException e) {
            startupError = e.toString();
            LOG.log(Level.WARNING, "Could not start the Claude Code integration server", e);
            return;
        }
        server = s;
        startupError = null;
        tracker = new SelectionTracker(s);
        tracker.install();
        projectsListener = e -> {
            if (OpenProjects.PROPERTY_OPEN_PROJECTS.equals(e.getPropertyName())) {
                s.refreshWorkspace();
            }
        };
        OpenProjects.getDefault().addPropertyChangeListener(projectsListener);
        // Projects restored from the last session finish opening after startup.
        RequestProcessor.getDefault().post(() -> {
            try {
                OpenProjects.getDefault().openProjects().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                LOG.log(Level.FINE, "project opening failed", e);
            }
            s.refreshWorkspace();
        });
    }

    synchronized void stop() {
        if (projectsListener != null) {
            OpenProjects.getDefault().removePropertyChangeListener(projectsListener);
            projectsListener = null;
        }
        if (tracker != null) {
            tracker.uninstall();
            tracker = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    @OnStart
    public static final class Startup implements Runnable {
        @Override
        public void run() {
            get().start();
        }
    }

    @OnStop
    public static final class Shutdown implements Runnable {
        @Override
        public void run() {
            ChatTopComponent.shutdownChat();
            get().stop();
        }
    }
}
