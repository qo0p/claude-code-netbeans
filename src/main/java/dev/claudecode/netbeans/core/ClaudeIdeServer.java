package dev.claudecode.netbeans.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The IDE side of Claude Code's editor-integration protocol: a loopback
 * WebSocket server speaking MCP (JSON-RPC 2.0), advertised through a lock file.
 */
public final class ClaudeIdeServer implements WebSocketServer.Listener {

    public static final String VERSION = "1.0.0";
    private static final Logger LOG = Logger.getLogger(ClaudeIdeServer.class.getName());
    private static final String DEFAULT_PROTOCOL = "2025-03-26";

    @FunctionalInterface
    private interface Handler {
        Object call(Map<String, Object> args) throws Exception;
    }

    private record Tool(String name, String description, Map<String, Object> schema, Handler handler) {
    }

    private final String ideName;
    private final Ide ide;
    private final String authToken = UUID.randomUUID().toString();
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "claude-ide-worker");
        t.setDaemon(true);
        return t;
    });

    private WebSocketServer ws;
    private volatile int port = -1;
    private volatile Path lockFile;
    private volatile Ide.Selection lastSelection;

    public ClaudeIdeServer(String ideName, Ide ide) {
        this.ideName = ideName;
        this.ide = ide;
        registerTools();
    }

    // ---------------------------------------------------------------- lifecycle

    public synchronized void start() throws IOException {
        if (ws != null) {
            return;
        }
        ws = new WebSocketServer(authToken, this);
        port = ws.start();
        writeLock();
        LOG.info("Claude Code IDE server listening on 127.0.0.1:" + port + ", lock file " + lockFile);
    }

    public synchronized void stop() {
        if (ws != null) {
            ws.stop();
            ws = null;
        }
        LockFile.delete(lockFile);
        lockFile = null;
        executor.shutdownNow();
    }

    /** Rewrites the lock file, e.g. after projects were opened or closed. */
    public synchronized void refreshWorkspace() {
        if (ws != null) {
            writeLock();
        }
    }

    private void writeLock() {
        try {
            lockFile = LockFile.write(port, ideName, authToken, ide.workspaceFolders());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write Claude Code lock file", e);
        }
    }

    public int port() {
        return port;
    }

    public Path lockFile() {
        return lockFile;
    }

    public int connectionCount() {
        WebSocketServer w = ws;
        return w == null ? 0 : w.connections().size();
    }

    String authToken() {
        return authToken;
    }

    // ---------------------------------------------------------------- outbound notifications

    /** Tells connected sessions what the user has selected (Claude sees it as context). */
    public void publishSelection(Ide.Selection s) {
        lastSelection = s;
        broadcast("selection_changed", selectionJson(s));
    }

    /** Equivalent of the VS Code "Insert @-mention" command. Lines are 0-based. */
    public void atMention(String filePath, int lineStart, int lineEnd) {
        broadcast("at_mentioned", Json.obj("filePath", filePath, "lineStart", lineStart, "lineEnd", lineEnd));
    }

    public void broadcast(String method, Map<String, Object> params) {
        WebSocketServer w = ws;
        if (w == null) {
            return;
        }
        String msg = Json.write(Json.obj("jsonrpc", "2.0", "method", method, "params", params));
        List<WebSocketServer.Connection> targets = w.connections();
        if (targets.isEmpty()) {
            return;
        }
        executor.execute(() -> targets.forEach(c -> c.send(msg)));
    }

    // ---------------------------------------------------------------- inbound

    @Override
    public void onOpen(WebSocketServer.Connection c) {
        LOG.info("Claude Code connected");
    }

    @Override
    public void onClose(WebSocketServer.Connection c) {
        LOG.info("Claude Code disconnected");
    }

    @Override
    public void onMessage(WebSocketServer.Connection c, String text) {
        Map<String, Object> msg;
        try {
            Object parsed = Json.parse(text);
            if (!(parsed instanceof Map)) {
                return;
            }
            msg = Json.map(parsed);
        } catch (IllegalArgumentException e) {
            c.send(Json.write(Json.obj("jsonrpc", "2.0", "id", null,
                    "error", Json.obj("code", -32700, "message", "Parse error"))));
            return;
        }
        Object id = msg.get("id");
        String method = Json.str(msg, "method");
        Map<String, Object> params = Json.map(msg.get("params"));
        if (method == null) {
            return; // a response to something we sent; we never send requests
        }
        LOG.fine(() -> "<- " + method + (id != null ? " #" + id : "")
                + ("tools/call".equals(method) ? " " + Json.str(params, "name") : ""));
        if (id == null) {
            if ("ide_connected".equals(method)) {
                // The CLI ignores selection updates that arrive in the same instant it
                // connects, so give it a moment before sharing the initial selection.
                executor.execute(() -> {
                    try {
                        Thread.sleep(750);
                    } catch (InterruptedException e) {
                        return;
                    }
                    sendCurrentSelection(c);
                });
            }
            return;
        }
        switch (method) {
            case "initialize":
                Object requested = params.get("protocolVersion");
                reply(c, id, Json.obj(
                        "protocolVersion", requested != null ? requested : DEFAULT_PROTOCOL,
                        "capabilities", Json.obj("tools", Json.obj("listChanged", true)),
                        "serverInfo", Json.obj("name", "claude-code-netbeans", "version", VERSION)));
                break;
            case "tools/list":
                List<Object> list = new ArrayList<>();
                for (Tool t : tools.values()) {
                    list.add(Json.obj("name", t.name(), "description", t.description(), "inputSchema", t.schema()));
                }
                reply(c, id, Json.obj("tools", list));
                break;
            case "tools/call":
                executor.execute(() -> reply(c, id, callTool(Json.str(params, "name"), Json.map(params.get("arguments")))));
                break;
            case "ping":
                reply(c, id, Json.obj());
                break;
            case "prompts/list":
                reply(c, id, Json.obj("prompts", List.of()));
                break;
            case "resources/list":
                reply(c, id, Json.obj("resources", List.of()));
                break;
            default:
                c.send(Json.write(Json.obj("jsonrpc", "2.0", "id", id,
                        "error", Json.obj("code", -32601, "message", "Method not found: " + method))));
        }
    }

    private void sendCurrentSelection(WebSocketServer.Connection c) {
        try {
            Ide.Selection s = ide.currentSelection();
            if (s != null) {
                lastSelection = s;
                c.send(Json.write(Json.obj("jsonrpc", "2.0", "method", "selection_changed",
                        "params", selectionJson(s))));
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "could not read selection", e);
        }
    }

    private static void reply(WebSocketServer.Connection c, Object id, Object result) {
        c.send(Json.write(Json.obj("jsonrpc", "2.0", "id", id, "result", result)));
    }

    Map<String, Object> callTool(String name, Map<String, Object> args) {
        Tool t = name == null ? null : tools.get(name);
        if (t == null) {
            return toolError("Unknown tool: " + name);
        }
        try {
            return Json.obj("content", content(t.handler().call(args)));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return toolError(cause.getMessage() != null ? cause.getMessage() : cause.toString());
        } catch (ToolException e) {
            return toolError(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return toolError("Interrupted");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Tool " + name + " failed", e);
            return toolError(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private static List<Object> content(Object r) {
        List<Object> out = new ArrayList<>();
        if (r instanceof List && !((List<?>) r).isEmpty()
                && ((List<?>) r).stream().allMatch(x -> x instanceof String)) {
            for (Object s : (List<?>) r) {
                out.add(Json.obj("type", "text", "text", s));
            }
        } else if (r instanceof String) {
            out.add(Json.obj("type", "text", "text", r));
        } else {
            out.add(Json.obj("type", "text", "text", Json.write(r)));
        }
        return out;
    }

    private static Map<String, Object> toolError(String message) {
        return Json.obj("content", List.of(Json.obj("type", "text", "text", "Error: " + message)), "isError", true);
    }

    // ---------------------------------------------------------------- tools

    private void tool(String name, String description, Map<String, Object> schema, Handler h) {
        tools.put(name, new Tool(name, description, schema, h));
    }

    private static Map<String, Object> schema(Object[][] props, String... required) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (Object[] prop : props) {
            p.put((String) prop[0], Json.obj("type", prop[1], "description", prop[2]));
        }
        return Json.obj("type", "object", "properties", p, "required", List.of(required));
    }

    private static String required(Map<String, Object> args, String key) {
        String v = Json.str(args, key);
        if (v == null) {
            throw new ToolException("Missing required argument: " + key);
        }
        return v;
    }

    private void registerTools() {
        tool("openFile", "Open a file in the editor and optionally select a range of text",
                schema(new Object[][]{
                    {"filePath", "string", "Path to the file to open"},
                    {"preview", "boolean", "Open in preview mode (ignored by NetBeans)"},
                    {"startText", "string", "Text marking the start of the selection"},
                    {"endText", "string", "Text marking the end of the selection"},
                    {"selectToEndOfLine", "boolean", "Extend the selection to the end of the line"},
                    {"makeFrontmost", "boolean", "Focus the editor (default true)"}}, "filePath"),
                a -> {
                    String path = required(a, "filePath");
                    boolean front = Json.bool(a, "makeFrontmost", true);
                    ide.openFile(path, Json.str(a, "startText"), Json.str(a, "endText"),
                            Json.bool(a, "selectToEndOfLine", false), front);
                    return front ? "Opened file: " + path
                            : Json.obj("success", true, "filePath", path, "languageId", languageId(path));
                });

        tool("openDiff", "Open a diff view comparing a file with proposed new contents and wait for the user to accept or reject",
                schema(new Object[][]{
                    {"old_file_path", "string", "Path of the existing file"},
                    {"new_file_path", "string", "Path the new contents will be written to"},
                    {"new_file_contents", "string", "Proposed contents"},
                    {"tab_name", "string", "Name of the diff tab"}},
                        "old_file_path", "new_file_path", "new_file_contents", "tab_name"),
                a -> {
                    String tab = required(a, "tab_name");
                    Ide.DiffResult r = ide.openDiff(required(a, "old_file_path"), required(a, "new_file_path"),
                            required(a, "new_file_contents"), tab).get();
                    return r.accepted() ? List.of("FILE_SAVED", r.contents()) : List.of("DIFF_REJECTED", tab);
                });

        tool("getCurrentSelection", "Get the current text selection in the active editor",
                schema(new Object[0][]),
                a -> {
                    Ide.Selection s = ide.currentSelection();
                    if (s == null) {
                        return Json.obj("success", false, "message", "No active editor found");
                    }
                    lastSelection = s;
                    Map<String, Object> m = Json.obj("success", true);
                    m.putAll(selectionJson(s));
                    return m;
                });

        tool("getLatestSelection", "Get the most recent text selection, even if the editor lost focus",
                schema(new Object[0][]),
                a -> {
                    Ide.Selection s = lastSelection != null ? lastSelection : ide.currentSelection();
                    if (s == null) {
                        return Json.obj("success", false, "message", "No selection available");
                    }
                    Map<String, Object> m = Json.obj("success", true);
                    m.putAll(selectionJson(s));
                    return m;
                });

        tool("getOpenEditors", "List the files currently open in the editor",
                schema(new Object[0][]),
                a -> {
                    List<Object> tabs = new ArrayList<>();
                    for (Ide.EditorTab t : ide.openEditors()) {
                        tabs.add(Json.obj("uri", fileUrl(t.filePath()), "isActive", t.active(),
                                "label", Path.of(t.filePath()).getFileName().toString(),
                                "languageId", languageId(t.filePath()), "isDirty", t.dirty()));
                    }
                    return Json.obj("tabs", tabs);
                });

        tool("getWorkspaceFolders", "List the projects open in the IDE",
                schema(new Object[0][]),
                a -> {
                    List<String> roots = ide.workspaceFolders();
                    List<Object> folders = new ArrayList<>();
                    for (String r : roots) {
                        Path p = Path.of(r);
                        folders.add(Json.obj("name", p.getFileName() != null ? p.getFileName().toString() : r,
                                "uri", fileUrl(r), "path", r));
                    }
                    return Json.obj("success", true, "folders", folders,
                            "rootPath", roots.isEmpty() ? null : roots.get(0));
                });

        tool("getDiagnostics", "Get errors and warnings reported by the IDE",
                schema(new Object[][]{{"uri", "string", "Optional file URI; all open files if omitted"}}),
                a -> ide.diagnostics(Json.str(a, "uri")));

        tool("checkDocumentDirty", "Check whether a document has unsaved changes",
                schema(new Object[][]{{"filePath", "string", "Path to the file"}}, "filePath"),
                a -> {
                    String path = required(a, "filePath");
                    Boolean dirty = ide.isDirty(path);
                    if (dirty == null) {
                        return Json.obj("success", false, "message", "Document not open: " + path);
                    }
                    return Json.obj("success", true, "filePath", path, "isDirty", dirty, "isUntitled", false);
                });

        tool("saveDocument", "Save a document with unsaved changes",
                schema(new Object[][]{{"filePath", "string", "Path to the file"}}, "filePath"),
                a -> {
                    String path = required(a, "filePath");
                    boolean saved = ide.save(path);
                    return Json.obj("success", true, "filePath", path, "saved", saved,
                            "message", saved ? "Document saved successfully" : "Document had no unsaved changes");
                });

        tool("close_tab", "Close a tab by name",
                schema(new Object[][]{{"tab_name", "string", "Name of the tab"}}, "tab_name"),
                a -> {
                    ide.closeTab(required(a, "tab_name"));
                    return "TAB_CLOSED";
                });

        tool("closeAllDiffTabs", "Close all diff tabs opened by Claude Code",
                schema(new Object[0][]),
                a -> "CLOSED_" + ide.closeAllDiffTabs() + "_DIFF_TABS");
    }

    // ---------------------------------------------------------------- helpers

    static Map<String, Object> selectionJson(Ide.Selection s) {
        return Json.obj(
                "text", s.text(),
                "filePath", s.filePath(),
                "fileUrl", fileUrl(s.filePath()),
                "selection", Json.obj(
                        "start", Json.obj("line", s.startLine(), "character", s.startCharacter()),
                        "end", Json.obj("line", s.endLine(), "character", s.endCharacter()),
                        "isEmpty", s.isEmpty()));
    }

    static String fileUrl(String path) {
        try {
            return Path.of(path).toUri().toString();
        } catch (RuntimeException e) {
            return "file://" + path;
        }
    }

    static String languageId(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "java": return "java";
            case "kt": case "kts": return "kotlin";
            case "groovy": case "gradle": return "groovy";
            case "js": case "mjs": case "cjs": return "javascript";
            case "ts": return "typescript";
            case "jsx": return "javascriptreact";
            case "tsx": return "typescriptreact";
            case "py": return "python";
            case "php": return "php";
            case "c": return "c";
            case "h": case "hpp": case "cpp": case "cc": case "cxx": return "cpp";
            case "html": case "htm": case "xhtml": return "html";
            case "css": return "css";
            case "xml": case "pom": return "xml";
            case "json": return "json";
            case "md": return "markdown";
            case "yml": case "yaml": return "yaml";
            case "sql": return "sql";
            case "sh": case "bash": return "shellscript";
            case "properties": return "properties";
            default: return "plaintext";
        }
    }
}
