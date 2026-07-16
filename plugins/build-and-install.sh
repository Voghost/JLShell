#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLUGINS_DIR="${HOME}/.jlshell/plugins"
PROGRAM_PLUGINS_DIR="${HOME}/.jlshell/program-plugins"

usage() {
    echo "Usage: $(basename "$0") <command>"
    echo ""
    echo "Commands:"
    echo "  install    Build demos under <plugin-id>/ while preserving each JAR filename"
    echo "  uninstall  Remove the four installed demo plugin directories"
    echo "  clean      Uninstall demo plugins and remove local build artifacts"
}

install_plugin() {
    module="$1"
    plugin_id="$2"
    root="$3"
    fatjar=""
    for candidate in "$module"/target/*-fat.jar; do
        [ -f "$candidate" ] || continue
        fatjar="$candidate"
        break
    done
    if [ -z "$fatjar" ]; then
        echo "Missing fat JAR for $module" >&2
        return 1
    fi

    plugin_dir="$root/$plugin_id"
    previous_dir="$plugin_dir/.previous"
    jar_name="$(basename "$fatjar")"
    mkdir -p "$plugin_dir"
    mkdir -p "$previous_dir"
    rm -f "$previous_dir"/*.jar
    for current in "$plugin_dir"/*.jar; do
        [ -f "$current" ] || continue
        [ "$(basename "$current")" = "previous-plugin.jar" ] && continue
        backup_name="$(basename "$current")"
        [ "$backup_name" = "plugin.jar" ] && backup_name="$jar_name"
        cp "$current" "$previous_dir/$backup_name"
        rm -f "$current"
    done
    rm -f "$plugin_dir/previous-plugin.jar"
    cp "$fatjar" "$plugin_dir/$jar_name"
    echo "Installed $plugin_id: $plugin_dir/$jar_name"
}

do_install() {
    echo "Building plugins..."
    cd "$SCRIPT_DIR"
    mvn clean package -q

    mkdir -p "$PLUGINS_DIR" "$PROGRAM_PLUGINS_DIR"

    # 清理旧脚本安装的平铺 JAR，避免同一插件同时从新旧目录加载。
    rm -f "$PROGRAM_PLUGINS_DIR"/plugin-program-demo-*-fat.jar
    rm -f "$PLUGINS_DIR"/plugin-session-demo-*-fat.jar \
        "$PLUGINS_DIR"/plugin-demo-*-fat.jar \
        "$PLUGINS_DIR"/plugin-sysmon-*-fat.jar

    install_plugin "plugin-program-demo" "com.jlshell.demo.program-host-tools" "$PROGRAM_PLUGINS_DIR"
    install_plugin "plugin-session-demo" "com.jlshell.demo.session-tools" "$PLUGINS_DIR"
    install_plugin "plugin-demo" "com.jlshell.demo.script-snippets" "$PLUGINS_DIR"
    install_plugin "plugin-sysmon" "com.jlshell.sysmon" "$PLUGINS_DIR"
    echo "Done. 4 demo plugins installed. Restart JLShell to reload plugin JARs."
}

do_uninstall() {
    removed=0
    for plugin_dir in \
        "$PROGRAM_PLUGINS_DIR/com.jlshell.demo.program-host-tools" \
        "$PLUGINS_DIR/com.jlshell.demo.session-tools" \
        "$PLUGINS_DIR/com.jlshell.demo.script-snippets" \
        "$PLUGINS_DIR/com.jlshell.sysmon"; do
        [ -d "$plugin_dir" ] || continue
        rm -rf "$plugin_dir"
        echo "Removed: $plugin_dir"
        removed=$((removed + 1))
    done
    rm -f "$PROGRAM_PLUGINS_DIR"/plugin-program-demo-*-fat.jar
    rm -f "$PLUGINS_DIR"/plugin-session-demo-*-fat.jar \
        "$PLUGINS_DIR"/plugin-demo-*-fat.jar \
        "$PLUGINS_DIR"/plugin-sysmon-*-fat.jar

    if [ $removed -eq 0 ]; then
        echo "No demo plugin directories found."
    else
        echo "Done. $removed demo plugin(s) removed."
    fi
}

do_clean() {
    do_uninstall
    echo "Cleaning build artifacts..."
    cd "$SCRIPT_DIR"
    mvn clean -q
    echo "Done."
}

case "${1:-}" in
    install)   do_install ;;
    uninstall) do_uninstall ;;
    clean)     do_clean ;;
    *)         usage ;;
esac
