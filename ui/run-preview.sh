#!/usr/bin/env bash
set -euo pipefail
preview_repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
preview_renderer=swing
preview_build=true
for option in "$@"; do
    case "$option" in
        --javafx) preview_renderer=javafx ;;
        --no-build) preview_build=false ;;
        *) echo "用法：ui/run-preview.sh [--javafx] [--no-build]" >&2; exit 2 ;;
    esac
done
preview_java_home="${JLSHELL_PREVIEW_JAVA_HOME:-${JAVA_HOME:-}}"
if [[ -z "$preview_java_home" || ! -x "$preview_java_home/bin/java" ]] ||
   ! "$preview_java_home/bin/java" -version 2>&1 | head -n 1 | grep -q '"21[.\"]'; then
    if [[ -x /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]]; then
        preview_java_home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
    elif [[ -x /usr/libexec/java_home ]]; then
        preview_java_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    fi
fi
if [[ ! -x "$preview_java_home/bin/java" ]] ||
   ! "$preview_java_home/bin/java" -version 2>&1 | head -n 1 | grep -q '"21[.\"]'; then
    echo "需要 JDK 21；请设置 JLSHELL_PREVIEW_JAVA_HOME。" >&2
    exit 1
fi
if [[ "$preview_build" == true ]]; then
    # package 不覆盖原工作目录，也不向 ~/.m2 安装同版本 UI 模块。
    (cd "$preview_repo" && JAVA_HOME="$preview_java_home" mvn -q package -DskipTests)
fi
preview_jar="$(ls -t "$preview_repo"/app/target/jlshell-app-*.jar 2>/dev/null | head -n 1)"
if [[ -z "$preview_jar" ]]; then echo "未找到预览 JAR，请先构建。" >&2; exit 1; fi
preview_home="${JLSHELL_PREVIEW_HOME:-$preview_repo/ui/target/preview-home}"
mkdir -p "$preview_home/.jlshell/plugins" "$preview_home/.jlshell/program-plugins"
preview_home="$(cd "$preview_home" && pwd)"
echo "预览渲染器：$preview_renderer"
echo "独立用户目录：$preview_home"
exec "$preview_java_home/bin/java" \
    --add-exports=java.desktop/sun.swing=ALL-UNNAMED \
    --add-opens=java.desktop/sun.swing=ALL-UNNAMED \
    --add-opens=java.desktop/javax.swing=ALL-UNNAMED \
    -Dapple.laf.useScreenMenuBar=true \
    -Dapple.awt.application.name=JLShell \
    -Duser.home="$preview_home" \
    -Djlshell.ui.preview=true \
    -Djlshell.ui.renderer="$preview_renderer" \
    -jar "$preview_jar"
