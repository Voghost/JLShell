#!/usr/bin/env bash
# =============================================================================
# JLShell Distribution Builder
# Produces self-contained packages with bundled JRE for macOS / Windows / Linux
#
# Usage:
#   ./build-dist.sh              # build for current platform only
#   ./build-dist.sh --all        # build for all platforms (requires cross JDKs)
#   ./build-dist.sh --mac        # macOS only
#   ./build-dist.sh --win        # Windows only
#   ./build-dist.sh --linux      # Linux only
# =============================================================================
set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
APP_NAME="JLShell"
APP_VERSION="0.1.0"
MAIN_CLASS="com.jlshell.app.Launcher"
MAIN_JAR="app-0.1.0-SNAPSHOT.jar"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="$SCRIPT_DIR/app/target"
DIST_DIR="$SCRIPT_DIR/dist"
FAT_JAR="$TARGET_DIR/$MAIN_JAR"

# JDK 21 locations — override via env vars if needed
JDK21_MAC="${JDK21_MAC:-/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home}"
JDK21_WIN="${JDK21_WIN:-}"   # path to a Windows JDK 21 (only needed for --win on non-Windows)
JDK21_LINUX="${JDK21_LINUX:-}"  # path to a Linux JDK 21 (only needed for --linux on non-Linux)

# JavaFX SDK paths for jlink (download from https://gluonhq.com/products/javafx/)
# Only needed if you want to jlink JavaFX modules too.
# Leave empty to skip JavaFX jlink (fat jar already contains natives).
JAVAFX_MODS_MAC="${JAVAFX_MODS_MAC:-}"
JAVAFX_MODS_WIN="${JAVAFX_MODS_WIN:-}"
JAVAFX_MODS_LINUX="${JAVAFX_MODS_LINUX:-}"

# ── Helpers ───────────────────────────────────────────────────────────────────
log()  { echo "▶ $*" >&2; }
ok()   { echo "✓ $*" >&2; }
err()  { echo "✗ $*" >&2; exit 1; }

require_cmd() { command -v "$1" &>/dev/null || err "Required command not found: $1"; }

# Detect current OS
current_os() {
    case "$(uname -s)" in
        Darwin) echo "mac" ;;
        Linux)  echo "linux" ;;
        MINGW*|MSYS*|CYGWIN*) echo "win" ;;
        *) echo "unknown" ;;
    esac
}

# ── Step 1: Maven build ───────────────────────────────────────────────────────
build_jar() {
    log "Building fat jar with cross-platform natives (profile: dist)..."
    mvn package -DskipTests -pl app -am -P dist -q
    [[ -f "$FAT_JAR" ]] || err "Fat jar not found: $FAT_JAR"
    ok "Fat jar: $FAT_JAR ($(du -sh "$FAT_JAR" | cut -f1))"
}

# ── Step 2: Required Java modules (fixed list, covers Spring Boot + JavaFX + SQLite + SSH) ──
detect_modules() {
    # jdeps on a shaded fat jar can be unreliable; use a curated list that covers all runtime needs.
    echo "java.base,java.compiler,java.datatransfer,java.desktop,java.instrument,java.logging,java.management,java.management.rmi,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.jfr,jdk.localedata,jdk.management,jdk.naming.dns,jdk.net,jdk.unsupported,jdk.unsupported.desktop,jdk.zipfs"
}

# ── Step 3: jlink ─────────────────────────────────────────────────────────────
run_jlink() {
    local jdk="$1"
    local modules="$2"
    local output="$3"
    local javafx_mods="$4"

    local jlink="$jdk/bin/jlink"
    [[ -x "$jlink" ]] || err "jlink not found at $jlink"

    rm -rf "$output"

    local module_path="$jdk/jmods"
    if [[ -n "$javafx_mods" && -d "$javafx_mods" ]]; then
        module_path="$javafx_mods:$module_path"
        # Add JavaFX modules
        modules="$modules,javafx.base,javafx.controls,javafx.fxml,javafx.graphics,javafx.swing"
    fi

    log "Running jlink → $output"
    "$jlink" \
        --module-path "$module_path" \
        --add-modules "$modules" \
        --output "$output" \
        --strip-debug \
        --no-man-pages \
        --no-header-files \
        --compress zip-6

    ok "JRE size: $(du -sh "$output" | cut -f1)"
}

# ── Step 4: Assemble packages ─────────────────────────────────────────────────

assemble_mac() {
    local jdk="${JDK21_MAC}"
    [[ -d "$jdk" ]] || err "macOS JDK 21 not found at $jdk — set JDK21_MAC env var"

    local work="$DIST_DIR/mac-work"
    rm -rf "$work" && mkdir -p "$work"

    # jlink a custom JRE for macOS
    local jre_dir="$work/jre"
    local modules
    modules=$(detect_modules)
    run_jlink "$jdk" "$modules" "$jre_dir" "${JAVAFX_MODS_MAC:-}"

    # Build AppIcon.icns (jpackage needs it before invocation)
    local icon_src="$SCRIPT_DIR/app/src/main/resources/icons/app_icon.png"
    local icns_file="$work/AppIcon.icns"
    if [[ -f "$icon_src" ]]; then
        local iconset="$work/AppIcon.iconset"
        mkdir -p "$iconset"
        for size in 16 32 128 256 512; do
            sips -z $size $size "$icon_src" --out "$iconset/icon_${size}x${size}.png" &>/dev/null
            sips -z $((size*2)) $((size*2)) "$icon_src" --out "$iconset/icon_${size}x${size}@2x.png" &>/dev/null
        done
        iconutil -c icns "$iconset" -o "$icns_file"
        rm -rf "$iconset"
        ok "macOS icon: AppIcon.icns created"
    else
        log "WARN: $icon_src not found, bundle will have no dock icon"
    fi

    # Use jpackage to create a proper .app bundle with a native launcher.
    # The native launcher sets the process name to "JLShell", so macOS shows
    # "Hide JLShell" / "Quit JLShell" instead of the Java class name.
    local jpackage="$jdk/bin/jpackage"
    [[ -x "$jpackage" ]] || err "jpackage not found at $jpackage"

    # jpackage --app-version requires first segment > 0
    local pkg_version="${APP_VERSION#0.}"
    pkg_version="1.${pkg_version}"

    # Prepare a clean input directory with only the fat jar
    local input_dir="$work/input"
    mkdir -p "$input_dir"
    cp "$FAT_JAR" "$input_dir/"

    log "Running jpackage → $APP_NAME.app"
    "$jpackage" \
        --type app-image \
        --name "$APP_NAME" \
        --app-version "$pkg_version" \
        --input "$input_dir" \
        --main-jar "$MAIN_JAR" \
        --main-class com.jlshell.app.Launcher \
        --runtime-image "$jre_dir" \
        --icon "$icns_file" \
        --vendor "JLShell" \
        --description "JLShell SSH Client" \
        --java-options "--add-opens java.base/java.lang=ALL-UNNAMED" \
        --java-options "--add-opens java.desktop/sun.awt=ALL-UNNAMED" \
        --java-options "-Dapple.laf.useScreenMenuBar=true" \
        --java-options "-Dapple.awt.application.name=JLShell" \
        --dest "$work"

    local app_bundle="$work/$APP_NAME.app"
    [[ -d "$app_bundle" ]] || err "jpackage did not produce $APP_NAME.app"
    ok "macOS .app bundle created"

    # Package as .zip (user can drag .app to Applications)
    local out="$DIST_DIR/${APP_NAME}-${APP_VERSION}-mac.zip"
    rm -f "$out"
    (cd "$work" && zip -qr "$out" "$APP_NAME.app")
    ok "macOS package: $out ($(du -sh "$out" | cut -f1))"
    rm -rf "$work"
}

assemble_linux() {
    local jdk="${JDK21_LINUX:-$JDK21_MAC}"  # fallback to mac JDK for module detection
    [[ -d "$jdk" ]] || err "Linux JDK 21 not found — set JDK21_LINUX env var"

    local work="$DIST_DIR/linux-work/$APP_NAME"
    rm -rf "$DIST_DIR/linux-work" && mkdir -p "$work"

    local modules
    modules=$(detect_modules)

    run_jlink "$jdk" "$modules" "$work/runtime" "${JAVAFX_MODS_LINUX:-}"

    cp "$FAT_JAR" "$work/$MAIN_JAR"

    cat > "$work/JLShell.sh" <<LAUNCHER
#!/bin/bash
DIR="\$(cd "\$(dirname "\$0")" && pwd)"
exec "\$DIR/runtime/bin/java" \\
    --add-opens java.base/java.lang=ALL-UNNAMED \\
    --add-opens java.desktop/sun.awt=ALL-UNNAMED \\
    -jar "\$DIR/$MAIN_JAR" "\$@"
LAUNCHER
    chmod +x "$work/JLShell.sh"

    # .desktop entry
    cat > "$work/JLShell.desktop" <<DESKTOP
[Desktop Entry]
Name=JLShell
Comment=SSH Client
Exec=/opt/jlshell/JLShell.sh
Icon=/opt/jlshell/icon.png
Terminal=false
Type=Application
Categories=Network;
DESKTOP

    local out="$DIST_DIR/${APP_NAME}-${APP_VERSION}-linux.tar.gz"
    rm -f "$out"
    (cd "$DIST_DIR/linux-work" && tar czf "$out" "$APP_NAME")
    ok "Linux package: $out ($(du -sh "$out" | cut -f1))"
    rm -rf "$DIST_DIR/linux-work"
}

assemble_win() {
    local jdk="${JDK21_WIN:-$JDK21_MAC}"  # fallback for module detection
    [[ -d "$jdk" ]] || err "Windows JDK 21 not found — set JDK21_WIN env var"

    local work="$DIST_DIR/win-work/$APP_NAME"
    rm -rf "$DIST_DIR/win-work" && mkdir -p "$work"

    local modules
    modules=$(detect_modules)

    run_jlink "$jdk" "$modules" "$work/runtime" "${JAVAFX_MODS_WIN:-}"

    # Build native Windows exe via Launch4j Maven plugin (jar is embedded in exe)
    log "Building Windows native exe via Launch4j..."
    mvn package -DskipTests -pl app -am -P dist,win-exe -q

    local maven_exe="$TARGET_DIR/${APP_NAME}.exe"
    if [[ -f "$maven_exe" ]]; then
        cp "$maven_exe" "$work/$APP_NAME.exe"
        ok "Windows native exe: $APP_NAME.exe"
    else
        # Fallback: batch launcher using javaw (no console window)
        log "WARN: Launch4j exe not found, falling back to .bat launcher"
        cat > "$work/JLShell.bat" <<'BAT'
@echo off
setlocal
set DIR=%~dp0
"%DIR%runtime\bin\javaw.exe" ^
    --add-opens java.base/java.lang=ALL-UNNAMED ^
    --add-opens java.desktop/sun.awt=ALL-UNNAMED ^
    -jar "%DIR%MAIN_JAR_PLACEHOLDER" %*
BAT
        sed -i '' "s/MAIN_JAR_PLACEHOLDER/$MAIN_JAR/" "$work/JLShell.bat" 2>/dev/null || \
        sed -i    "s/MAIN_JAR_PLACEHOLDER/$MAIN_JAR/" "$work/JLShell.bat"
    fi

    local out="$DIST_DIR/${APP_NAME}-${APP_VERSION}-win.zip"
    rm -f "$out"
    (cd "$DIST_DIR/win-work" && zip -qr "$out" "$APP_NAME")
    ok "Windows package: $out ($(du -sh "$out" | cut -f1))"
    rm -rf "$DIST_DIR/win-work"
}

# ── Main ──────────────────────────────────────────────────────────────────────
require_cmd mvn
require_cmd zip
require_cmd java

mkdir -p "$DIST_DIR"

# Parse args
BUILD_MAC=false; BUILD_WIN=false; BUILD_LINUX=false
if [[ $# -eq 0 ]]; then
    case "$(current_os)" in
        mac)   BUILD_MAC=true ;;
        win)   BUILD_WIN=true ;;
        linux) BUILD_LINUX=true ;;
    esac
else
    for arg in "$@"; do
        case "$arg" in
            --all)   BUILD_MAC=true; BUILD_WIN=true; BUILD_LINUX=true ;;
            --mac)   BUILD_MAC=true ;;
            --win)   BUILD_WIN=true ;;
            --linux) BUILD_LINUX=true ;;
            *) err "Unknown argument: $arg" ;;
        esac
    done
fi

build_jar

$BUILD_MAC   && assemble_mac
$BUILD_LINUX && assemble_linux
$BUILD_WIN   && assemble_win

echo ""
log "Done. Packages in: $DIST_DIR/"
ls -lh "$DIST_DIR"/*.zip "$DIST_DIR"/*.tar.gz 2>/dev/null || true
