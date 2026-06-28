#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLUGINS_DIR="${HOME}/.jlshell/plugins"
PROGRAM_PLUGINS_DIR="${HOME}/.jlshell/program-plugins"

usage() {
    echo "Usage: $(basename "$0") <command>"
    echo ""
    echo "Commands:"
    echo "  install    Build all plugins and install to ~/.jlshell/plugins/ and ~/.jlshell/program-plugins/"
    echo "  uninstall  Remove installed plugin demo JARs from both plugin directories"
    echo "  clean      Remove all installed plugins AND local build artifacts"
}

do_install() {
    echo "Building plugins..."
    cd "$SCRIPT_DIR"
    mvn clean package -q

    mkdir -p "$PLUGINS_DIR" "$PROGRAM_PLUGINS_DIR"

    installed=0
    for fatjar in */target/*-fat.jar; do
        [ -f "$fatjar" ] || continue
        name="$(basename "$fatjar")"
        module="${fatjar%%/*}"
        if [ "$module" = "plugin-program-demo" ]; then
            cp "$fatjar" "$PROGRAM_PLUGINS_DIR/$name"
            echo "Installed program plugin: $name"
        else
            cp "$fatjar" "$PLUGINS_DIR/$name"
            echo "Installed session plugin: $name"
        fi
        installed=$((installed + 1))
    done

    if [ $installed -eq 0 ]; then
        echo "No plugin fat JARs found."
    else
        echo "Done. $installed plugin(s) installed."
        echo "Session plugins: $PLUGINS_DIR"
        echo "Program plugins: $PROGRAM_PLUGINS_DIR"
    fi
}

do_uninstall() {
    removed=0
    for dir in "$PLUGINS_DIR" "$PROGRAM_PLUGINS_DIR"; do
        [ -d "$dir" ] || continue
        for jar in "$dir"/*-fat.jar; do
            [ -f "$jar" ] || continue
            rm "$jar"
            echo "Removed: $(basename "$jar")"
            removed=$((removed + 1))
        done
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
