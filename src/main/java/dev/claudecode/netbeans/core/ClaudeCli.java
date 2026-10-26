package dev.claudecode.netbeans.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the claude executable. IDEs started from a desktop launcher often have
 * a much shorter PATH than a terminal, so common install locations are searched
 * too. Override with -Dclaude.code.cli=/path/to/claude.
 */
public final class ClaudeCli {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private ClaudeCli() {
    }

    /** The executable, or null if it can't be found. */
    public static File find() {
        String configured = System.getProperty("claude.code.cli");
        if (configured != null && !configured.isBlank()) {
            File f = new File(configured.trim());
            return f.isFile() ? f : null;
        }
        String[] names = WINDOWS ? new String[]{"claude.exe", "claude.cmd"} : new String[]{"claude"};
        for (File dir : searchPath()) {
            for (String n : names) {
                File f = new File(dir, n);
                if (f.isFile() && (WINDOWS || f.canExecute())) {
                    return f;
                }
            }
        }
        return null;
    }

    /** How to launch it (wrapping .cmd scripts on Windows). */
    public static List<String> command(File exe) {
        String p = exe.getAbsolutePath();
        String lower = p.toLowerCase(Locale.ROOT);
        if (WINDOWS && (lower.endsWith(".cmd") || lower.endsWith(".bat"))) {
            return List.of("cmd.exe", "/c", p);
        }
        return List.of(p);
    }

    /**
     * Environment for the child process: puts the executable's folder first on
     * PATH, because an npm-installed claude needs the node next to it.
     */
    public static Map<String, String> environment(File exe) {
        Map<String, String> env = new LinkedHashMap<>();
        String path = System.getenv(WINDOWS ? pathKey() : "PATH");
        String dir = exe.getParentFile().getAbsolutePath();
        env.put(WINDOWS ? pathKey() : "PATH", path == null || path.isEmpty() ? dir : dir + File.pathSeparator + path);
        return env;
    }

    /** Folders that are searched, in order. */
    public static List<File> searchPath() {
        List<File> dirs = new ArrayList<>();
        String path = System.getenv(WINDOWS ? pathKey() : "PATH");
        if (path != null) {
            for (String d : path.split(File.pathSeparator)) {
                if (!d.isBlank()) {
                    dirs.add(new File(d));
                }
            }
        }
        String home = System.getenv(WINDOWS ? "USERPROFILE" : "HOME");
        if (home == null || home.isBlank()) {
            home = System.getProperty("user.home");
        }
        if (WINDOWS) {
            dirs.add(new File(home, ".local\\bin"));
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                dirs.add(new File(appData, "npm"));
            }
        } else {
            for (String d : new String[]{".local/bin", ".claude/local", ".npm-global/bin", ".bun/bin",
                    ".volta/bin", ".yarn/bin", "bin"}) {
                dirs.add(new File(home, d));
            }
            dirs.addAll(nvmBins(new File(home, ".nvm/versions/node")));
            dirs.addAll(nvmBins(new File(home, ".local/share/fnm/node-versions")));
            for (String d : new String[]{"/usr/local/bin", "/opt/homebrew/bin", "/usr/bin", "/snap/bin"}) {
                dirs.add(new File(d));
            }
        }
        return dirs;
    }

    private static List<File> nvmBins(File versions) {
        File[] vs = versions.listFiles(File::isDirectory);
        if (vs == null) {
            return List.of();
        }
        List<File> bins = new ArrayList<>();
        Arrays.sort(vs, Comparator.comparing(File::getName).reversed());
        for (File v : vs) {
            bins.add(new File(v, "bin"));
            bins.add(new File(v, "installation/bin"));
        }
        return bins;
    }

    private static String pathKey() {
        for (String k : System.getenv().keySet()) {
            if (k.equalsIgnoreCase("PATH")) {
                return k;
            }
        }
        return "Path";
    }
}
