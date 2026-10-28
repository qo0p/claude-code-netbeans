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

# ---------------------------------------------------------------- NetBeans user folders
# Folders holding config/, modules/ and var/log/messages.log, one per NetBeans version.
# The plugin is installed into every one that already has it; if none has it yet,
# into the one NetBeans used most recently. NB_USERDIR picks one yourself.
find_userdirs() {
    if [ -n "${NB_USERDIR:-}" ]; then echo "$NB_USERDIR"; return; fi
    local newest="" newest_time=0 d t found=0
    for d in "$HOME/snap/netbeans/current" "$HOME"/.local/share/NetBeans/*/ "$HOME"/.netbeans/*/ \
             "$HOME/Library/Application Support/NetBeans"/*/; do
        d="${d%/}"
        [ -f "$d/var/log/messages.log" ] || continue
        # skip very old NetBeans (8.x and earlier can't run this plugin)
        grep -q "Product Version *= *NetBeans IDE [0-8]\." "$d/var/log/messages.log" 2>/dev/null && continue
        d=$(cd "$d" && pwd -P)
        if [ -f "$d/modules/dev-claudecode-netbeans.jar" ]; then
            echo "$d"; found=1
        fi
        t=$(stat -c %Y "$d/var/log/messages.log" 2>/dev/null || stat -f %m "$d/var/log/messages.log")
        if [ "$t" -gt "$newest_time" ]; then newest="$d"; newest_time="$t"; fi
    done
    if [ "$found" -eq 0 ] && [ -n "$newest" ]; then echo "$newest"; fi
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

mapfile -t USERDIRS < <(find_userdirs | awk '!seen[$0]++')
[ "${#USERDIRS[@]}" -gt 0 ] || fail "Couldn't find your NetBeans user folder. Start NetBeans once, or set NB_USERDIR."
LOG="${USERDIRS[0]}/var/log/messages.log"

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

for U in "${USERDIRS[@]}"; do
    say "NetBeans user folder: $U ($(sed -n 's/^ *Product Version *= *//p' "$U/var/log/messages.log" | tail -n 1))"
done
say "Maven: $MVN (Java $JV${JAVA_HOME:+, $JAVA_HOME})"
say "Source: $PWD"

# ---------------------------------------------------------------- NetBeans must be closed
# NetBeans keeps a "lock" file in its user folder while it runs.
for U in "${USERDIRS[@]}"; do
    if [ -e "$U/lock" ] && pgrep -f "org.netbeans.Main|netbeans/platform/lib/boot.jar" >/dev/null 2>&1 \
       && [ "${1:-}" != "--force" ]; then
        fail "NetBeans is still running. Quit it first (File > Exit), then run this again."
    fi
done

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
say ""
for U in "${USERDIRS[@]}"; do
    mkdir -p "$U/modules" "$U/config/Modules"
    extract "$NBM" netbeans/modules/dev-claudecode-netbeans.jar "$U/modules/dev-claudecode-netbeans.jar.new" \
        && mv -f "$U/modules/dev-claudecode-netbeans.jar.new" "$U/modules/dev-claudecode-netbeans.jar" \
        || fail "Couldn't copy the plugin into $U/modules"
    if [ ! -f "$U/config/Modules/dev-claudecode-netbeans.xml" ]; then
        extract "$NBM" netbeans/config/Modules/dev-claudecode-netbeans.xml "$U/config/Modules/dev-claudecode-netbeans.xml"
    fi
    say "Installed into $U/modules/dev-claudecode-netbeans.jar"
done
# show the version stamped into the jar that was just built
JAR_TMP=$(mktemp)
if extract "$NBM" netbeans/modules/dev-claudecode-netbeans.jar "$JAR_TMP" 2>/dev/null; then
    PROPS=$(extract "$JAR_TMP" dev/claudecode/netbeans/version.properties /dev/stdout 2>/dev/null)
    V=$(printf '%s\n' "$PROPS" | sed -n 's/^version=//p')
    B=$(printf '%s\n' "$PROPS" | sed -n 's/^built=//p')
    B_LOCAL=$(date -d "$B" '+%-d %b %H:%M' 2>/dev/null || echo "$B")
    say "Plugin version: v${V:-?} · built ${B_LOCAL:-?}  (the chat window header shows the same)"
fi
rm -f "$JAR_TMP"
say "Start NetBeans and open Tools > Claude Code Chat (Ctrl+Esc)."
