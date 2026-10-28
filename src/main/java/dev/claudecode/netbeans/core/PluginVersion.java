package dev.claudecode.netbeans.core;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Properties;

/** The plugin's version and build time, stamped in by Maven (see version.properties). */
public final class PluginVersion {

    private static final String VERSION;
    private static final Instant BUILT;

    static {
        Properties p = new Properties();
        try (InputStream in = PluginVersion.class.getResourceAsStream("/dev/claudecode/netbeans/version.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException ignored) {
        }
        VERSION = clean(p.getProperty("version"));
        Instant built = null;
        String b = clean(p.getProperty("built"));
        if (b != null) {
            try {
                built = Instant.parse(b);
            } catch (DateTimeParseException ignored) {
            }
        }
        BUILT = built;
    }

    private PluginVersion() {
    }

    /** e.g. "1.1.0", or null when not built by Maven. */
    public static String version() {
        return VERSION;
    }

    /** When this build was made, or null. */
    public static Instant built() {
        return BUILT;
    }

    /** Short form for the chat header, e.g. "v1.1.0 · built 28 Oct 10:21". */
    public static String shortText() {
        return shortText(ZoneId.systemDefault());
    }

    static String shortText(ZoneId zone) {
        if (VERSION == null && BUILT == null) {
            return "development build";
        }
        StringBuilder sb = new StringBuilder();
        if (VERSION != null) {
            sb.append('v').append(VERSION);
        }
        if (BUILT != null) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("built ").append(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH).withZone(zone).format(BUILT));
        }
        return sb.toString();
    }

    /** Long form for tooltips and logs, e.g. "1.1.0, built 2026-10-28 10:21:05 +05:00". */
    public static String longText() {
        String v = VERSION != null ? VERSION : "development build";
        if (BUILT == null) {
            return v;
        }
        return v + ", built " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ENGLISH)
                .withZone(ZoneId.systemDefault()).format(BUILT);
    }

    /** Unfiltered placeholders (a build not done by Maven) count as unknown. */
    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() || s.contains("${") ? null : s;
    }
}
