package dev.claudecode.netbeans.chat;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * The live "what is Claude doing right now" line at the bottom of the chat:
 * an animated spinner, the current phase, and a ticking timer, so it is
 * always clear whether Claude is working or stuck.
 */
final class ActivityRow extends JPanel {

    private static final long serialVersionUID = 1L;
    /** No event for this long (while not waiting on a tool or on the user) counts as a possible stall. */
    private static final long STALL_MS = 30_000;

    private final Spinner spinner = new Spinner();
    private final JLabel text = new JLabel(" ");
    private final JLabel detail = new JLabel(" ");
    private final Timer timer;
    private final transient Runnable onTick;

    private long turnStart;
    private long lastEvent;
    private String phase = "";
    private String extra = "";
    private boolean waitingOnUser;
    private boolean toolRunning;

    ActivityRow(Runnable onTick) {
        super(new BorderLayout(8, 0));
        this.onTick = onTick;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        JPanel lines = new JPanel(new BorderLayout(0, 2));
        lines.setOpaque(false);
        text.setForeground(HtmlView.Theme.fg());
        detail.setForeground(HtmlView.Theme.dim());
        detail.setVisible(false);
        lines.add(text, BorderLayout.NORTH);
        lines.add(detail, BorderLayout.SOUTH);
        JPanel spinnerBox = new JPanel(new BorderLayout());
        spinnerBox.setOpaque(false);
        spinnerBox.add(spinner, BorderLayout.NORTH);
        add(spinnerBox, BorderLayout.WEST);
        add(lines, BorderLayout.CENTER);
        timer = new Timer(80, e -> tick());
    }

    void start() {
        turnStart = System.currentTimeMillis();
        lastEvent = turnStart;
        phase = "Sending to Claude";
        extra = "";
        waitingOnUser = false;
        toolRunning = false;
        setDetail(null);
        timer.start();
        refresh();
    }

    void stop() {
        timer.stop();
    }

    String phase() {
        return phase;
    }

    boolean isRunning() {
        return timer.isRunning();
    }

    /** Records that the CLI sent something, which resets the stall warning. */
    void touch() {
        lastEvent = System.currentTimeMillis();
    }

    void setPhase(String phase, String extra) {
        this.phase = phase;
        this.extra = extra == null ? "" : extra;
        this.waitingOnUser = false;
        this.toolRunning = false;
        touch();
        refresh();
    }

    void setToolRunning(String phase, String extra) {
        setPhase(phase, extra);
        toolRunning = true;
    }

    void setWaitingOnUser(String phase) {
        setPhase(phase, null);
        waitingOnUser = true;
        refresh();
    }

    void setExtra(String extra) {
        this.extra = extra == null ? "" : extra;
        touch();
        refresh();
    }

    void setDetail(String summary) {
        boolean show = summary != null && !summary.isBlank();
        detail.setText(show ? summary : " ");
        if (detail.isVisible() != show) {
            detail.setVisible(show);
            revalidate();
        }
    }

    private void tick() {
        spinner.advance(!waitingOnUser);
        refresh();
        if (onTick != null) {
            onTick.run();
        }
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("<html>");
        sb.append("<b>").append(HtmlEsc.esc(phase)).append("…</b>");
        if (!extra.isEmpty()) {
            sb.append(" <span style='color:").append(HtmlView.Theme.hex(HtmlView.Theme.dim())).append("'>")
                    .append(HtmlEsc.esc(extra)).append("</span>");
        }
        sb.append(" <span style='color:").append(HtmlView.Theme.hex(HtmlView.Theme.dim())).append("'>· ")
                .append(duration(now - turnStart)).append("</span>");
        long quiet = now - lastEvent;
        if (!waitingOnUser && !toolRunning && quiet > STALL_MS) {
            sb.append(" <span style='color:").append(HtmlView.Theme.hex(HtmlView.Theme.error())).append("'>· no response for ")
                    .append(duration(quiet)).append(quiet > 120_000 ? " — press Stop to cancel" : "").append("</span>");
        }
        sb.append("</html>");
        text.setText(sb.toString());
        spinner.repaint();
    }

    static String duration(long ms) {
        long s = ms / 1000;
        if (s < 60) {
            return s + "s";
        }
        return String.format(Locale.ROOT, "%dm %02ds", s / 60, s % 60);
    }

    /** Small rotating arc; a still dot while waiting for the user. */
    private static final class Spinner extends JComponent {
        private static final long serialVersionUID = 1L;
        private int angle;
        private boolean moving = true;

        Spinner() {
            setPreferredSize(new Dimension(14, 16));
        }

        void advance(boolean move) {
            moving = move;
            if (move) {
                angle = (angle + 30) % 360;
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color accent = HtmlView.Theme.accent();
            int d = 11;
            int x = (getWidth() - d) / 2;
            int y = (getHeight() - d) / 2 + 1;
            if (moving) {
                g2.setColor(HtmlView.Theme.border());
                g2.setStroke(new BasicStroke(2f));
                g2.drawOval(x, y, d, d);
                g2.setColor(accent);
                g2.drawArc(x, y, d, d, -angle, 100);
            } else {
                g2.setColor(accent);
                g2.fillOval(x + 3, y + 3, d - 6, d - 6);
            }
            g2.dispose();
        }
    }

    static final class HtmlEsc {
        private HtmlEsc() {
        }

        static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
