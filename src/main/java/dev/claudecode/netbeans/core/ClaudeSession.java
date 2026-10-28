package dev.claudecode.netbeans.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Drives one Claude Code conversation through the CLI's headless streaming
 * mode ({@code claude -p --input-format stream-json --output-format stream-json}),
 * the same mechanism the official VS Code panel uses. Permission prompts are
 * routed to the host ({@code --permission-prompt-tool stdio}).
 *
 * Listener callbacks arrive on a background reader thread.
 */
public final class ClaudeSession {

    private static final Logger LOG = Logger.getLogger(ClaudeSession.class.getName());

    public interface Listener {
        default void onInit(String sessionId, String model) {
        }

        /** A chunk of assistant text as it streams in. */
        default void onTextDelta(String text) {
        }

        /** The complete text of one assistant text block (supersedes the deltas). */
        default void onAssistantText(String text) {
        }

        default void onToolUse(String toolUseId, String name, Map<String, Object> input) {
        }

        /** Claude started a thinking block. */
        default void onThinkingStart() {
        }

        /** Thinking text, when the CLI provides it (it is often withheld; then this isn't called). */
        default void onThinkingDelta(String text) {
        }

        /** Running estimate of the tokens in the current thinking block. */
        default void onThinkingTokens(int estimatedTokens) {
        }

        default void onThinkingEnd() {
        }

        /** Claude is writing the input for a tool call; {@code chars} is how much it has written so far. */
        default void onToolInputProgress(String toolName, int chars) {
        }

        /** A one-line description of what Claude is currently doing. */
        default void onTaskSummary(String summary) {
        }

        default void onToolResult(String toolUseId, String text, boolean isError) {
        }

        default void onPermissionRequest(PermissionRequest request) {
        }

        /** e.g. "requesting", "compacting"; null when idle again. */
        default void onStatus(String status) {
        }

        default void onTurnComplete(TurnResult result) {
        }

        default void onExit(int exitCode, String stderrTail) {
        }
    }

    public record PermissionRequest(String requestId, String toolName, Map<String, Object> input,
                                    String toolUseId, String description) {
    }

    public record TurnResult(String subtype, boolean error, double costUsd, long durationMs, String text) {
        public boolean interrupted() {
            return "error_during_execution".equals(subtype);
        }
    }

    private final List<String> command;
    private final File workingDirectory;
    private final Map<String, String> environment;
    private final Listener listener;
    private final AtomicLong ids = new AtomicLong();
    private final Deque<String> stderrTail = new ArrayDeque<>();

    private Process process;
    private Writer stdin;
    private volatile String sessionId;
    private volatile String model;

    public ClaudeSession(List<String> command, File workingDirectory, Map<String, String> environment,
                         Listener listener) {
        this.command = List.copyOf(command);
        this.workingDirectory = workingDirectory;
        this.environment = environment;
        this.listener = listener;
    }

    /**
     * @param cli            how to run claude, e.g. ["/usr/bin/claude"] or ["cmd.exe","/c","claude.cmd"]
     * @param permissionMode "default", "acceptEdits" or "plan"; null for the CLI default
     * @param resumeSessionId continue an earlier conversation, or null
     */
    public static List<String> buildCommand(List<String> cli, String permissionMode, String resumeSessionId) {
        List<String> c = new ArrayList<>(cli);
        c.addAll(List.of("-p",
                "--input-format", "stream-json",
                "--output-format", "stream-json",
                "--verbose",
                "--include-partial-messages",
                "--permission-prompt-tool", "stdio",
                // the panel can't render its multiple-choice UI; Claude asks in plain text instead
                "--disallowedTools", "AskUserQuestion"));
        if (permissionMode != null && !permissionMode.equals("default")) {
            c.addAll(List.of("--permission-mode", permissionMode));
        }
        if (resumeSessionId != null && !resumeSessionId.isBlank()) {
            c.addAll(List.of("--resume", resumeSessionId));
        }
        return c;
    }

    public synchronized void start() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command).directory(workingDirectory);
        if (environment != null) {
            pb.environment().putAll(environment);
        }
        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Process p = process;
        daemon("claude-session-stderr", () -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (stderrTail) {
                        stderrTail.addLast(line);
                        if (stderrTail.size() > 40) {
                            stderrTail.removeFirst();
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }).start();
        daemon("claude-session-stdout", () -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) {
                        try {
                            handle(line);
                        } catch (RuntimeException e) {
                            LOG.log(Level.WARNING, "Could not handle Claude Code output: " + abbreviate(line), e);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
            int code;
            try {
                code = p.waitFor();
            } catch (InterruptedException e) {
                code = -1;
            }
            String tail;
            synchronized (stderrTail) {
                tail = String.join("\n", stderrTail);
            }
            listener.onExit(code, tail);
        }).start();
        control("initialize", Json.obj());
    }

    public String sessionId() {
        return sessionId;
    }

    public String model() {
        return model;
    }

    public synchronized boolean isAlive() {
        return process != null && process.isAlive();
    }

    public void sendUserMessage(String text) {
        write(Json.obj("type", "user",
                "message", Json.obj("role", "user", "content", text),
                "parent_tool_use_id", null,
                "session_id", sessionId != null ? sessionId : ""));
    }

    public void allow(PermissionRequest r, Map<String, Object> updatedInput) {
        respond(r.requestId(), Json.obj("behavior", "allow",
                "updatedInput", updatedInput != null ? updatedInput : r.input()));
    }

    public void deny(PermissionRequest r, String message) {
        respond(r.requestId(), Json.obj("behavior", "deny", "message", message));
    }

    /** Stops the current turn; the conversation stays usable. */
    public void interrupt() {
        control("interrupt", Json.obj());
    }

    public void setPermissionMode(String mode) {
        control("set_permission_mode", Json.obj("mode", mode));
    }

    public void stop() {
        Process p;
        synchronized (this) {
            p = process;
            try {
                if (stdin != null) {
                    stdin.close();
                }
            } catch (IOException ignored) {
            }
            stdin = null;
        }
        if (p == null) {
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ protocol

    private void control(String subtype, Map<String, Object> fields) {
        Map<String, Object> req = Json.obj("subtype", subtype);
        req.putAll(fields);
        write(Json.obj("type", "control_request", "request_id", "nb-" + ids.incrementAndGet(), "request", req));
    }

    private void respond(String requestId, Object response) {
        write(Json.obj("type", "control_response",
                "response", Json.obj("subtype", "success", "request_id", requestId, "response", response)));
    }

    private synchronized void write(Map<String, Object> message) {
        if (stdin == null) {
            return;
        }
        try {
            stdin.write(Json.write(message));
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            LOG.log(Level.FINE, "write to Claude Code failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    void handle(String line) {
        Object parsed;
        try {
            parsed = Json.parse(line);
        } catch (IllegalArgumentException e) {
            return; // not JSON (e.g. a warning printed to stdout)
        }
        if (!(parsed instanceof Map)) {
            return;
        }
        Map<String, Object> o = Json.map(parsed);
        String type = Json.str(o, "type");
        if (type == null) {
            return;
        }
        boolean topLevel = o.get("parent_tool_use_id") == null; // skip sub-agent chatter
        switch (type) {
            case "system": {
                String sub = Json.str(o, "subtype");
                if ("init".equals(sub)) {
                    sessionId = Json.str(o, "session_id");
                    model = Json.str(o, "model");
                    listener.onInit(sessionId, model);
                } else if ("status".equals(sub)) {
                    listener.onStatus(Json.str(o, "status"));
                } else if ("thinking_tokens".equals(sub)) {
                    Object n = o.get("estimated_tokens");
                    if (n instanceof Number) {
                        listener.onThinkingTokens(((Number) n).intValue());
                    }
                } else if ("task_summary".equals(sub)) {
                    String detail = Json.str(o, "detail");
                    if (detail != null && !detail.isBlank()) {
                        listener.onTaskSummary(detail.strip());
                    }
                } else if (sub != null && sub.contains("retry")) {
                    listener.onStatus("retrying");
                } else if ("compact_boundary".equals(sub)) {
                    listener.onStatus("compacted");
                }
                break;
            }
            case "stream_event": {
                if (!topLevel) {
                    break;
                }
                handleStreamEvent(Json.map(o.get("event")));
                break;
            }
            case "assistant": {
                if (!topLevel) {
                    break;
                }
                for (Object b : list(Json.map(o.get("message")).get("content"))) {
                    Map<String, Object> block = Json.map(b);
                    String bt = Json.str(block, "type");
                    if ("text".equals(bt)) {
                        listener.onAssistantText(Json.str(block, "text"));
                    } else if ("tool_use".equals(bt)) {
                        listener.onToolUse(Json.str(block, "id"), Json.str(block, "name"), Json.map(block.get("input")));
                    }
                }
                break;
            }
            case "user": {
                if (!topLevel) {
                    break;
                }
                for (Object b : list(Json.map(o.get("message")).get("content"))) {
                    Map<String, Object> block = Json.map(b);
                    if ("tool_result".equals(Json.str(block, "type"))) {
                        listener.onToolResult(Json.str(block, "tool_use_id"), flatten(block.get("content")),
                                Json.bool(block, "is_error", false));
                    }
                }
                break;
            }
            case "control_request": {
                String id = Json.str(o, "request_id");
                Map<String, Object> req = Json.map(o.get("request"));
                if ("can_use_tool".equals(Json.str(req, "subtype"))) {
                    listener.onPermissionRequest(new PermissionRequest(id, Json.str(req, "tool_name"),
                            Json.map(req.get("input")), Json.str(req, "tool_use_id"), Json.str(req, "description")));
                } else {
                    write(Json.obj("type", "control_response", "response", Json.obj("subtype", "error",
                            "request_id", id, "error", "Not supported by the NetBeans panel: " + Json.str(req, "subtype"))));
                }
                break;
            }
            case "result": {
                if (Json.str(o, "session_id") != null) {
                    sessionId = Json.str(o, "session_id");
                }
                listener.onTurnComplete(new TurnResult(Json.str(o, "subtype"), Json.bool(o, "is_error", false),
                        number(o.get("total_cost_usd")), (long) number(o.get("duration_ms")), Json.str(o, "result")));
                break;
            }
            default:
                break;
        }
    }

    /** Content blocks of the message being streamed, by index: their type and (for tools) name. */
    private final Map<Long, String[]> openBlocks = new java.util.HashMap<>();
    private final Map<Long, Integer> toolInputChars = new java.util.HashMap<>();

    private void handleStreamEvent(Map<String, Object> ev) {
        String et = Json.str(ev, "type");
        if (et == null) {
            return;
        }
        Long index = ev.get("index") instanceof Number ? ((Number) ev.get("index")).longValue() : null;
        switch (et) {
            case "message_start":
                openBlocks.clear();
                toolInputChars.clear();
                break;
            case "content_block_start": {
                Map<String, Object> block = Json.map(ev.get("content_block"));
                String bt = Json.str(block, "type");
                if (index != null && bt != null) {
                    openBlocks.put(index, new String[]{bt, Json.str(block, "name")});
                }
                if ("thinking".equals(bt) || "redacted_thinking".equals(bt)) {
                    listener.onThinkingStart();
                } else if ("tool_use".equals(bt)) {
                    listener.onToolInputProgress(Json.str(block, "name"), 0);
                }
                break;
            }
            case "content_block_delta": {
                Map<String, Object> delta = Json.map(ev.get("delta"));
                String dt = Json.str(delta, "type");
                if ("text_delta".equals(dt)) {
                    String t = Json.str(delta, "text");
                    if (t != null) {
                        listener.onTextDelta(t);
                    }
                } else if ("thinking_delta".equals(dt)) {
                    String t = Json.str(delta, "thinking");
                    if (t != null && !t.isEmpty()) {
                        listener.onThinkingDelta(t);
                    }
                } else if ("input_json_delta".equals(dt) && index != null) {
                    String part = Json.str(delta, "partial_json");
                    int chars = toolInputChars.merge(index, part == null ? 0 : part.length(), Integer::sum);
                    String[] info = openBlocks.get(index);
                    listener.onToolInputProgress(info != null ? info[1] : null, chars);
                }
                break;
            }
            case "content_block_stop": {
                String[] info = index == null ? null : openBlocks.remove(index);
                if (info != null && ("thinking".equals(info[0]) || "redacted_thinking".equals(info[0]))) {
                    listener.onThinkingEnd();
                }
                break;
            }
            default:
                break;
        }
    }

    private static List<?> list(Object o) {
        return o instanceof List ? (List<?>) o : List.of();
    }

    private static double number(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }

    static String flatten(Object content) {
        if (content instanceof String) {
            return (String) content;
        }
        StringBuilder sb = new StringBuilder();
        for (Object part : list(content)) {
            Map<String, Object> m = Json.map(part);
            if ("text".equals(Json.str(m, "type")) && m.get("text") != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(m.get("text"));
            }
        }
        return sb.toString();
    }

    private static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
