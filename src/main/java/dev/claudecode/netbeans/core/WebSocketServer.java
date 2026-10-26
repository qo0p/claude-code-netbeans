package dev.claudecode.netbeans.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small RFC 6455 WebSocket server bound to the loopback interface only.
 * Every upgrade request must carry the {@code x-claude-code-ide-authorization}
 * header with the token written to the lock file; anything else gets a 401.
 */
public final class WebSocketServer {

    public interface Listener {
        void onOpen(Connection c);

        void onMessage(Connection c, String message);

        void onClose(Connection c);
    }

    public static final String AUTH_HEADER = "x-claude-code-ide-authorization";
    private static final Logger LOG = Logger.getLogger(WebSocketServer.class.getName());
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE = 64 * 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final AtomicInteger THREADS = new AtomicInteger();

    private final String token;
    private final Listener listener;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public WebSocketServer(String token, Listener listener) {
        this.token = token;
        this.listener = listener;
    }

    /** Binds to a free loopback port and starts accepting. Returns the port. */
    public int start() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50);
        serverSocket = ss;
        running = true;
        daemon("claude-ide-accept", this::acceptLoop).start();
        return ss.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        for (Connection c : new ArrayList<>(connections)) {
            c.close(1001);
        }
        connections.clear();
    }

    public List<Connection> connections() {
        return new ArrayList<>(connections);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                daemon("claude-ide-conn-" + THREADS.incrementAndGet(), () -> handle(s)).start();
            } catch (IOException e) {
                if (running) {
                    LOG.log(Level.FINE, "accept failed", e);
                }
            }
        }
    }

    private void handle(Socket s) {
        Connection c = null;
        try {
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            Map<String, String> h = readHeaders(in);
            if (h == null) {
                s.close();
                return;
            }
            String key = h.get("sec-websocket-key");
            if (key == null || !"websocket".equalsIgnoreCase(h.getOrDefault("upgrade", "").trim())) {
                httpError(out, "400 Bad Request");
                s.close();
                return;
            }
            String auth = h.get(AUTH_HEADER);
            if (auth == null || !MessageDigest.isEqual(
                    auth.trim().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
                LOG.warning("Rejected WebSocket connection with missing or wrong auth token");
                httpError(out, "401 Unauthorized");
                s.close();
                return;
            }
            StringBuilder resp = new StringBuilder()
                    .append("HTTP/1.1 101 Switching Protocols\r\n")
                    .append("Upgrade: websocket\r\n")
                    .append("Connection: Upgrade\r\n")
                    .append("Sec-WebSocket-Accept: ").append(acceptKey(key.trim())).append("\r\n");
            String proto = h.get("sec-websocket-protocol");
            if (proto != null && !proto.isBlank()) {
                resp.append("Sec-WebSocket-Protocol: ").append(proto.split(",")[0].trim()).append("\r\n");
            }
            resp.append("\r\n");
            out.write(resp.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            c = new Connection(s, out);
            connections.add(c);
            listener.onOpen(c);
            c.readLoop(new DataInputStream(in));
        } catch (EOFException e) {
            // peer went away
        } catch (IOException e) {
            LOG.log(Level.FINE, "connection error", e);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "unexpected error in connection", e);
        } finally {
            if (c != null) {
                connections.remove(c);
                c.closeSocket();
                listener.onClose(c);
            } else {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static Map<String, String> readHeaders(InputStream in) throws IOException {
        int[] budget = {MAX_HEADER_BYTES};
        String requestLine = readLine(in, budget);
        if (requestLine == null || !requestLine.startsWith("GET ")) {
            return null;
        }
        Map<String, String> h = new HashMap<>();
        while (true) {
            String line = readLine(in, budget);
            if (line == null) {
                return null;
            }
            if (line.isEmpty()) {
                return h;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                h.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
        }
    }

    private static String readLine(InputStream in, int[] budget) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0 || --budget[0] < 0) {
                return null;
            }
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                b.write(c);
            }
        }
        return b.toString(StandardCharsets.ISO_8859_1);
    }

    private static void httpError(OutputStream out, String status) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    static String acceptKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return Base64.getEncoder().encodeToString(
                    sha1.digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** One connected client (normally a Claude Code CLI session). */
    public final class Connection {
        private final Socket socket;
        private final OutputStream out;
        private volatile boolean open = true;

        Connection(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        public boolean isOpen() {
            return open;
        }

        /** Sends a text message; returns false (and closes) if the socket is dead. */
        public boolean send(String text) {
            try {
                sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
                return true;
            } catch (IOException e) {
                closeSocket();
                return false;
            }
        }

        public void close(int code) {
            try {
                sendFrame(0x8, new byte[]{(byte) (code >> 8), (byte) code});
            } catch (IOException ignored) {
            }
            closeSocket();
        }

        void closeSocket() {
            open = false;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
            if (!open) {
                throw new IOException("closed");
            }
            int len = payload.length;
            out.write(0x80 | opcode);
            if (len < 126) {
                out.write(len);
            } else if (len <= 0xFFFF) {
                out.write(126);
                out.write(len >>> 8);
                out.write(len & 0xFF);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) {
                    out.write((int) (((long) len >>> (8 * i)) & 0xFF));
                }
            }
            out.write(payload);
            out.flush();
        }

        void readLoop(DataInputStream in) throws IOException {
            ByteArrayOutputStream message = null;
            int messageOpcode = 0;
            while (open) {
                int b0 = in.read();
                if (b0 < 0) {
                    return;
                }
                int b1 = in.readUnsignedByte();
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = in.readUnsignedShort();
                } else if (len == 127) {
                    len = in.readLong();
                }
                if (len < 0 || len > MAX_MESSAGE) {
                    close(1009);
                    return;
                }
                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    in.readFully(mask);
                }
                byte[] payload = new byte[(int) len];
                in.readFully(payload);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i & 3];
                    }
                }

                if (opcode >= 0x8) {
                    if (opcode == 0x8) {
                        close(payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1000);
                        return;
                    } else if (opcode == 0x9) {
                        sendFrame(0xA, payload);
                    }
                    continue;
                }
                if (opcode == 0x1 || opcode == 0x2) {
                    message = new ByteArrayOutputStream();
                    messageOpcode = opcode;
                } else if (opcode != 0x0 || message == null) {
                    close(1002);
                    return;
                }
                message.write(payload);
                if (message.size() > MAX_MESSAGE) {
                    close(1009);
                    return;
                }
                if (fin) {
                    if (messageOpcode == 0x1) {
                        listener.onMessage(this, message.toString(StandardCharsets.UTF_8));
                    }
                    message = null;
                }
            }
        }
    }
}
