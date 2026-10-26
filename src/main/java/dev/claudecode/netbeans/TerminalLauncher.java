package dev.claudecode.netbeans;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Opens the operating system's terminal in a folder and runs {@code claude}
 * with the environment that makes it connect to this NetBeans instance.
 *
 * Overrides (add to netbeans_default_options in etc/netbeans.conf):
 *   -J-Dclaude.code.cli=/full/path/to/claude
 *   -J-Dclaude.terminal="wezterm start --"   (Linux: terminal command; the shell command is appended)
 */
public final class TerminalLauncher {

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private TerminalLauncher() {
    }

    static String cli() {
        return System.getProperty("claude.code.cli", "claude");
    }

    public static void launch(File dir, int port) throws IOException {
        if (OS.contains("win")) {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "start", "\"Claude Code\"", "cmd.exe", "/k", cli());
            pb.directory(dir);
            env(pb, port);
            pb.start();
        } else if (OS.contains("mac")) {
            String script = "cd " + sh(dir.getAbsolutePath()) + " && " + exports(port) + " && " + cli();
            String apple = script.replace("\\", "\\\\").replace("\"", "\\\"");
            new ProcessBuilder("osascript",
                    "-e", "tell application \"Terminal\"",
                    "-e", "activate",
                    "-e", "do script \"" + apple + "\"",
                    "-e", "end tell").start();
        } else {
            String script = "cd " + sh(dir.getAbsolutePath()) + " && " + exports(port) + " && " + cli()
                    + "; exec \"${SHELL:-/bin/bash}\"";
            List<String> cmd = linuxTerminal();
            if (cmd == null) {
                throw new IOException("No terminal emulator found. Set -J-Dclaude.terminal in etc/netbeans.conf");
            }
            cmd.addAll(List.of("bash", "-lc", script));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir);
            env(pb, port);
            pb.start();
        }
    }

    /** The command a user can paste into any terminal (including NetBeans' own) instead. */
    public static String manualCommand(File dir, int port) {
        if (OS.contains("win")) {
            return "cd /d \"" + dir.getAbsolutePath() + "\" & set \"CLAUDE_CODE_SSE_PORT=" + port
                    + "\" & set \"ENABLE_IDE_INTEGRATION=true\" & " + cli();
        }
        return "cd " + sh(dir.getAbsolutePath()) + " && CLAUDE_CODE_SSE_PORT=" + port
                + " ENABLE_IDE_INTEGRATION=true " + cli();
    }

    private static void env(ProcessBuilder pb, int port) {
        pb.environment().put("CLAUDE_CODE_SSE_PORT", Integer.toString(port));
        pb.environment().put("ENABLE_IDE_INTEGRATION", "true");
    }

    private static String exports(int port) {
        return "export CLAUDE_CODE_SSE_PORT=" + port + " ENABLE_IDE_INTEGRATION=true";
    }

    private static String sh(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static List<String> linuxTerminal() {
        String custom = System.getProperty("claude.terminal");
        if (custom != null && !custom.isBlank()) {
            return new ArrayList<>(Arrays.asList(custom.trim().split("\\s+")));
        }
        String[][] candidates = {
            {"gnome-terminal", "--"},
            {"konsole", "-e"},
            {"xfce4-terminal", "-x"},
            {"kitty"},
            {"alacritty", "-e"},
            {"tilix", "-e"},
            {"x-terminal-emulator", "-e"},
            {"xterm", "-e"},
        };
        for (String[] c : candidates) {
            if (onPath(c[0])) {
                return new ArrayList<>(Arrays.asList(c));
            }
        }
        return null;
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, exe).canExecute()) {
                return true;
            }
        }
        return false;
    }
}
