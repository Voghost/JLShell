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
APP_VERSION="${APP_VERSION:-$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null | sed 's/\.RELEASE$//')}"
MAIN_CLASS="com.jlshell.launcher.BootstrapLauncher"
LAUNCHER_MAIN_JAR="jlshell-launcher.jar"
BUNDLED_APP_JAR="jlshell-app-bundled.jar"
JLSHELL_JVM_XMS="${JLSHELL_JVM_XMS:-64m}"
JLSHELL_JVM_XMX="${JLSHELL_JVM_XMX:-512m}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="$SCRIPT_DIR/app/target"
DIST_DIR="$SCRIPT_DIR/dist"
APP_JAR=""
LAUNCHER_JAR=""

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

write_vmoptions_file() {
    local file="$1"
    cat > "$file" <<EOF
# JLShell JVM options. One option per line.
# User override path on Linux: ~/.config/jlshell/JLShell.vmoptions
-Xms$JLSHELL_JVM_XMS
-Xmx$JLSHELL_JVM_XMX
-XX:+ExplicitGCInvokesConcurrent
EOF
}

# ── Helpers ───────────────────────────────────────────────────────────────────
log()  { echo "▶ $*" >&2; }
ok()   { echo "✓ $*" >&2; }
err()  { echo "✗ $*" >&2; exit 1; }

require_cmd() { command -v "$1" &>/dev/null || err "Required command not found: $1"; }

sign_mac_bundle() {
    local app_bundle="$1"
    if ! command -v codesign &>/dev/null; then
        log "WARN: codesign not found; macOS may report the app as damaged after download"
        return
    fi

    local identity="${JLSHELL_MAC_SIGN_IDENTITY:--}"
    if [[ "$identity" == "-" ]]; then
        log "Ad-hoc signing macOS app bundle for local distribution"
        codesign --force --deep --sign - "$app_bundle"
    else
        log "Signing macOS app bundle with identity: $identity"
        codesign --force --deep --options runtime --timestamp --sign "$identity" "$app_bundle"
    fi
    codesign --verify --deep --strict --verbose=2 "$app_bundle"
}

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
    log "Building launcher and app jar with cross-platform natives (profile: dist)..."
    mvn package -DskipTests -pl launcher,app -am -P dist -q
    APP_JAR="$(ls "$SCRIPT_DIR"/app/target/jlshell-app-*.jar | head -1)"
    LAUNCHER_JAR="$(ls "$SCRIPT_DIR"/launcher/target/jlshell-launcher-*.jar | head -1)"
    [[ -f "$APP_JAR" ]] || err "App jar not found"
    [[ -f "$LAUNCHER_JAR" ]] || err "Launcher jar not found"
    ok "App jar: $APP_JAR ($(du -sh "$APP_JAR" | cut -f1))"
    ok "Launcher jar: $LAUNCHER_JAR ($(du -sh "$LAUNCHER_JAR" | cut -f1))"
}

# ── Step 2: Required Java modules (fixed list, covers Spring Boot + JavaFX + SQLite + SSH) ──
detect_modules() {
    # jdeps on a shaded fat jar can be unreliable; use a curated list that covers all runtime needs.
    echo "java.base,java.compiler,java.datatransfer,java.desktop,java.instrument,java.logging,java.management,java.management.rmi,java.naming,java.net.http,jdk.httpserver,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.jfr,jdk.localedata,jdk.management,jdk.naming.dns,jdk.net,jdk.unsupported,jdk.unsupported.desktop,jdk.zipfs"
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

    # Prepare a clean input directory with the stable launcher and bundled app jar.
    local input_dir="$work/input"
    mkdir -p "$input_dir/app"
    cp "$LAUNCHER_JAR" "$input_dir/$LAUNCHER_MAIN_JAR"
    cp "$APP_JAR" "$input_dir/app/$BUNDLED_APP_JAR"

    log "Running jpackage → $APP_NAME.app"
    "$jpackage" \
        --type app-image \
        --name "$APP_NAME" \
        --app-version "$pkg_version" \
        --input "$input_dir" \
        --main-jar "$LAUNCHER_MAIN_JAR" \
        --main-class "$MAIN_CLASS" \
        --runtime-image "$jre_dir" \
        --icon "$icns_file" \
        --vendor "JLShell" \
        --description "JLShell SSH Client" \
        --java-options "-Xms$JLSHELL_JVM_XMS" \
        --java-options "-Xmx$JLSHELL_JVM_XMX" \
        --java-options "-XX:+ExplicitGCInvokesConcurrent" \
        --java-options "--add-opens java.base/java.lang=ALL-UNNAMED" \
        --java-options "--add-opens java.desktop/sun.awt=ALL-UNNAMED" \
        --java-options "-Dapple.laf.useScreenMenuBar=true" \
        --java-options "-Dapple.awt.application.appearance=system" \
        --java-options "-Dapple.awt.application.name=JLShell" \
        --dest "$work"

    local app_bundle="$work/$APP_NAME.app"
    [[ -d "$app_bundle" ]] || err "jpackage did not produce $APP_NAME.app"
    if command -v /usr/libexec/PlistBuddy >/dev/null 2>&1; then
        /usr/libexec/PlistBuddy -c "Delete :NSRequiresAquaSystemAppearance" \
            "$app_bundle/Contents/Info.plist" >/dev/null 2>&1 || true
        /usr/libexec/PlistBuddy -c "Add :NSRequiresAquaSystemAppearance bool false" \
            "$app_bundle/Contents/Info.plist"
    fi
    mkdir -p "$app_bundle/Contents/Resources/zh-Hans.lproj"
    cat > "$app_bundle/Contents/Resources/zh-Hans.lproj/InfoPlist.strings" <<'EOF'
"CFBundleDisplayName" = "JLShell";
"CFBundleName" = "JLShell";
"NSHumanReadableCopyright" = "版权所有";
EOF
    ok "macOS .app bundle created"

    sign_mac_bundle "$app_bundle"

    # Package as .zip (user can drag .app to Applications)
    local out="$DIST_DIR/${APP_NAME}-${APP_VERSION}-mac.zip"
    rm -f "$out"
    (cd "$work" && ditto -c -k --keepParent "$APP_NAME.app" "$out")
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

    mkdir -p "$work/app"
    cp "$LAUNCHER_JAR" "$work/$LAUNCHER_MAIN_JAR"
    cp "$APP_JAR" "$work/app/$BUNDLED_APP_JAR"
    write_vmoptions_file "$work/JLShell.vmoptions"

    cat > "$work/JLShell.sh" <<LAUNCHER
#!/bin/bash
DIR="\$(cd "\$(dirname "\$0")" && pwd)"
DEFAULT_VM_OPTS=(-Xms64m -Xmx512m -XX:+ExplicitGCInvokesConcurrent)
USER_VMOPTIONS="\${XDG_CONFIG_HOME:-\$HOME/.config}/jlshell/JLShell.vmoptions"
BUNDLED_VMOPTIONS="\$DIR/JLShell.vmoptions"
VMOPTIONS_FILE=""
if [[ -f "\$USER_VMOPTIONS" ]]; then
    VMOPTIONS_FILE="\$USER_VMOPTIONS"
elif [[ -f "\$BUNDLED_VMOPTIONS" ]]; then
    VMOPTIONS_FILE="\$BUNDLED_VMOPTIONS"
fi
VM_OPTS=("\${DEFAULT_VM_OPTS[@]}")
if [[ -n "\$VMOPTIONS_FILE" ]]; then
    CUSTOM_VM_OPTS=()
    while IFS= read -r line || [[ -n "\$line" ]]; do
        line="\${line%%#*}"
        line="\$(echo "\$line" | xargs)"
        [[ -z "\$line" ]] && continue
        CUSTOM_VM_OPTS+=("\$line")
    done < "\$VMOPTIONS_FILE"
    if [[ \${#CUSTOM_VM_OPTS[@]} -gt 0 ]] && "\$DIR/runtime/bin/java" "\${CUSTOM_VM_OPTS[@]}" -version >/dev/null 2>&1; then
        VM_OPTS=("\${CUSTOM_VM_OPTS[@]}")
    fi
fi
exec "\$DIR/runtime/bin/java" "\${VM_OPTS[@]}" \\
    --add-opens java.base/java.lang=ALL-UNNAMED \\
    --add-opens java.desktop/sun.awt=ALL-UNNAMED \\
    -jar "\$DIR/$LAUNCHER_MAIN_JAR" "\$@"
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

    mkdir -p "$work/app"
    cp "$LAUNCHER_JAR" "$work/$LAUNCHER_MAIN_JAR"
    cp "$APP_JAR" "$work/app/$BUNDLED_APP_JAR"
    write_vmoptions_file "$work/JLShell.vmoptions"
    cat > "$work/JLShell.bat" <<'BAT'
@echo off
setlocal
set DIR=%~dp0
set JAVA=%DIR%runtime\bin\javaw.exe
set VM_OPTS=-Xms64m -Xmx512m -XX:+ExplicitGCInvokesConcurrent
set USER_VMOPTIONS=%APPDATA%\JLShell\JLShell.vmoptions
set BUNDLED_VMOPTIONS=%DIR%JLShell.vmoptions
set VMOPTIONS_FILE=
if exist "%USER_VMOPTIONS%" (
    set VMOPTIONS_FILE=%USER_VMOPTIONS%
) else if exist "%BUNDLED_VMOPTIONS%" (
    set VMOPTIONS_FILE=%BUNDLED_VMOPTIONS%
)
if not "%VMOPTIONS_FILE%"=="" (
    set CUSTOM_VM_OPTS=
    for /f "usebackq eol=# tokens=* delims=" %%A in ("%VMOPTIONS_FILE%") do call set CUSTOM_VM_OPTS=%%CUSTOM_VM_OPTS%% %%A
    if not "%CUSTOM_VM_OPTS%"=="" (
        "%JAVA%" %CUSTOM_VM_OPTS% -version >nul 2>&1
        if not errorlevel 1 set VM_OPTS=%CUSTOM_VM_OPTS%
    )
)
"%JAVA%" %VM_OPTS% ^
    --add-opens java.base/java.lang=ALL-UNNAMED ^
    --add-opens java.desktop/sun.awt=ALL-UNNAMED ^
    -jar "%DIR%MAIN_JAR_PLACEHOLDER" %*
BAT
    sed -i '' "s/MAIN_JAR_PLACEHOLDER/$LAUNCHER_MAIN_JAR/" "$work/JLShell.bat" 2>/dev/null || \
    sed -i    "s/MAIN_JAR_PLACEHOLDER/$LAUNCHER_MAIN_JAR/" "$work/JLShell.bat"

    # plugins directory — drop plugin JARs here to install them
    mkdir -p "$work/plugins"
    cat > "$work/plugins/README.txt" <<'EOF'
Place plugin JAR files (*.jar) in this directory.
JLShell will automatically discover and load them on startup.
EOF

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
