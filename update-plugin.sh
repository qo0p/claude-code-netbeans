#!/usr/bin/env bash
# Builds the Claude Code plugin and installs it into your NetBeans.
# Quit NetBeans first, run ./update-plugin.sh, then start NetBeans again.
#
# It finds Maven and your NetBeans user folder by itself. To choose them yourself:
#   MVN=/path/to/mvn NB_USERDIR=/path/to/netbeans/userdir ./update-plugin.sh
set -uo pipefail
cd "$(dirname "$0")"

say()  { printf '%s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- NetBeans user folder
# The folder holding config/, modules/ and var/log/messages.log. Prefer the one that
# already has this plugin, then the one NetBeans used most recently.
find_userdir() {
    if [ -n "${NB_USERDIR:-}" ]; then echo "$NB_USERDIR"; return; fi
    local best="" best_time=0 has_plugin=0 d t p
    for d in "$HOME/snap/netbeans/current" "$HOME"/.local/share/NetBeans/*/ "$HOME"/.netbeans/*/ \
             "$HOME/Library/Application Support/NetBeans"/*/; do
        d="${d%/}"
        [ -f "$d/var/log/messages.log" ] || continue
        # skip very old NetBeans (8.x and earlier can't run this plugin)
        grep -q "Product Version *= *NetBeans IDE [0-8]\." "$d/var/log/messages.log" 2>/dev/null && continue
        t=$(stat -c %Y "$d/var/log/messages.log" 2>/dev/null || stat -f %m "$d/var/log/messages.log")
        p=0; [ -f "$d/modules/dev-claudecode-netbeans.jar" ] && p=1
        if [ "$p" -gt "$has_plugin" ] || { [ "$p" -eq "$has_plugin" ] && [ "$t" -gt "$best_time" ]; }; then
            best="$d"; best_time="$t"; has_plugin="$p"
        fi
    done
    [ -n "$best" ] && echo "$best"
}

# ---------------------------------------------------------------- Maven
find_mvn() {
    if [ -n "${MVN:-}" ]; then echo "$MVN"; return; fi
    local m
    m=$(command -v mvn 2>/dev/null) && { echo "$m"; return; }
    # standalone installs, newest version first
    m=$(ls -d "$HOME"/app/*maven*/bin/mvn "$HOME"/tools/*maven*/bin/mvn "$HOME"/*maven*/bin/mvn \
              /opt/*maven*/bin/mvn /usr/local/*maven*/bin/mvn /usr/share/maven/bin/mvn 2>/dev/null \
        | sort -V | tail -n 1)
    [ -n "$m" ] && [ -x "$m" ] && { echo "$m"; return; }
    # the Maven bundled with a modern NetBeans
    for m in /snap/netbeans/current/netbeans/java/maven/bin/mvn /usr/lib/apache-netbeans/java/maven/bin/mvn \
             /opt/netbeans/java/maven/bin/mvn /opt/apache-netbeans/java/maven/bin/mvn \
             "$HOME"/netbeans*/java/maven/bin/mvn "$HOME"/app/netbeans-[1-9][0-9]*/java/maven/bin/mvn; do
        [ -x "$m" ] && { echo "$m"; return; }
    done
    return 1
}

java_major() { # prints the major version of the java Maven would use
    "$1" -v 2>/dev/null | sed -n 's/^Java version: \([0-9]*\).*/\1/p' | head -n 1
}

USERDIR=$(find_userdir) || true
[ -n "$USERDIR" ] || fail "Couldn't find your NetBeans user folder. Start NetBeans once, or set NB_USERDIR."
USERDIR=$(cd "$USERDIR" && pwd -P)
LOG="$USERDIR/var/log/messages.log"

MVN=$(find_mvn) || fail "Couldn't find Maven. Install it, or run: MVN=/path/to/mvn $0"

# Maven needs JDK 17+. If it would use an older Java, borrow the JDK NetBeans runs on.
JV=$(java_major "$MVN")
if [ -z "$JV" ] || [ "$JV" -lt 17 ]; then
    NBJAVA=$(sed -n 's/^ *Java Home *= *//p' "$LOG" | tail -n 1)
    if [ -n "$NBJAVA" ] && [ -x "$NBJAVA/bin/java" ]; then
        export JAVA_HOME="$NBJAVA"
        JV=$(java_major "$MVN")
    fi
fi
[ -n "$JV" ] && [ "$JV" -ge 17 ] || fail "Maven needs JDK 17 or newer (found: ${JV:-none}). Set JAVA_HOME to a JDK 17+."

say "NetBeans user folder: $USERDIR"
say "  $(sed -n 's/^ *Product Version *= *//p' "$LOG" | tail -n 1)"
say "Maven: $MVN (Java $JV${JAVA_HOME:+, $JAVA_HOME})"

# ---------------------------------------------------------------- NetBeans must be closed
# NetBeans keeps a "lock" file in its user folder while it runs.
if [ -e "$USERDIR/lock" ] && pgrep -f "org.netbeans.Main|netbeans/platform/lib/boot.jar" >/dev/null 2>&1 \
   && [ "${1:-}" != "--force" ]; then
    fail "NetBeans is still running. Quit it first (File > Exit), then run this again."
fi

# ---------------------------------------------------------------- build
say "Building…"
BUILD_LOG=target/update-plugin-build.log
mkdir -p target
if ! "$MVN" -B -q package >"$BUILD_LOG" 2>&1; then
    if [ -f build-settings.xml ]; then
        say "Build with your Maven settings failed; retrying against Maven Central…"
        if ! "$MVN" -B -q -U -s build-settings.xml -gs build-settings.xml package >"$BUILD_LOG" 2>&1; then
            tail -n 30 "$BUILD_LOG" >&2
            fail "Build failed. Full log: $PWD/$BUILD_LOG"
        fi
    else
        tail -n 30 "$BUILD_LOG" >&2
        fail "Build failed. Full log: $PWD/$BUILD_LOG"
    fi
fi
NBM=$(ls -t target/*.nbm 2>/dev/null | head -n 1)
[ -n "$NBM" ] || fail "Build finished but no .nbm was produced."

# ---------------------------------------------------------------- install
extract() { # extract <zip> <entry> <dest-file>
    if command -v unzip >/dev/null 2>&1; then
        unzip -p "$1" "$2" > "$3"
    else
        python3 -c 'import sys,zipfile; sys.stdout.buffer.write(zipfile.ZipFile(sys.argv[1]).read(sys.argv[2]))' "$1" "$2" > "$3"
    fi
}
mkdir -p "$USERDIR/modules" "$USERDIR/config/Modules"
extract "$NBM" netbeans/modules/dev-claudecode-netbeans.jar "$USERDIR/modules/dev-claudecode-netbeans.jar.new" \
    && mv -f "$USERDIR/modules/dev-claudecode-netbeans.jar.new" "$USERDIR/modules/dev-claudecode-netbeans.jar" \
    || fail "Couldn't copy the plugin into $USERDIR/modules"
if [ ! -f "$USERDIR/config/Modules/dev-claudecode-netbeans.xml" ]; then
    extract "$NBM" netbeans/config/Modules/dev-claudecode-netbeans.xml "$USERDIR/config/Modules/dev-claudecode-netbeans.xml"
fi

say ""
say "Installed into $USERDIR/modules/dev-claudecode-netbeans.jar"
say "Start NetBeans and open Tools > Claude Code Chat (Ctrl+Esc)."
