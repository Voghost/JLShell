#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLUGINS_DIR="${HOME}/.jlshell/plugins"

usage() {
    echo "Usage: $(basename "$0") <command>"
    echo ""
    echo "Commands:"
    echo "  install    Build all plugins and install to ~/.jlshell/plugins/"
    echo "  uninstall  Remove all installed plugins from ~/.jlshell/plugins/"
    echo "  clean      Remove all installed plugins AND local build artifacts"
}

do_install() {
    echo "Building plugins..."
    cd "$SCRIPT_DIR"
    mvn clean package -q

    mkdir -p "$PLUGINS_DIR"

    installed=0
    for fatjar in */target/*-fat.jar; do
        [ -f "$fatjar" ] || continue
        name="$(basename "$fatjar")"
        cp "$fatjar" "$PLUGINS_DIR/$name"
        echo "Installed: $name"
        installed=$((installed + 1))
    done

    if [ $installed -eq 0 ]; then
        echo "No plugin fat JARs found."
    else
        echo "Done. $installed plugin(s) installed to $PLUGINS_DIR"
    fi
}

do_uninstall() {
    if [ ! -d "$PLUGINS_DIR" ]; then
        echo "Plugin directory does not exist: $PLUGINS_DIR"
        return
    fi

    removed=0
    for jar in "$PLUGINS_DIR"/*-fat.jar; do
        [ -f "$jar" ] || continue
        rm "$jar"
        echo "Removed: $(basename "$jar")"
        removed=$((removed + 1))
    done

    if [ $removed -eq 0 ]; then
        echo "No plugins found in $PLUGINS_DIR"
    else
        echo "Done. $removed plugin(s) removed from $PLUGINS_DIR"
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
