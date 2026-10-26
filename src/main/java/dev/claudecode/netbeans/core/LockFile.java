package dev.claudecode.netbeans.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;

/**
 * The discovery file Claude Code reads: {@code ~/.claude/ide/<port>.lock}
 * (or {@code $CLAUDE_CONFIG_DIR/ide/<port>.lock}).
 */
public final class LockFile {

    private LockFile() {
    }

    public static Path directory() {
        String cfg = System.getenv("CLAUDE_CONFIG_DIR");
        Path base = cfg != null && !cfg.isBlank()
                ? Path.of(cfg)
                : Path.of(System.getProperty("user.home"), ".claude");
        return base.resolve("ide");
    }

    public static Path write(int port, String ideName, String authToken, List<String> workspaceFolders)
            throws IOException {
        Path dir = directory();
        Files.createDirectories(dir);
        Path file = dir.resolve(port + ".lock");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String json = Json.write(Json.obj(
                "pid", ProcessHandle.current().pid(),
                "workspaceFolders", workspaceFolders,
                "ideName", ideName,
                "transport", "ws",
                "runningInWindows", windows,
                "authToken", authToken));

        Path tmp = Files.createTempFile(dir, port + "-", ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows: the user profile directory is already private
            }
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        file.toFile().deleteOnExit();
        return file;
    }

    public static void delete(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
