#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
preview_options=()
has_preview_options=false
use_real_data=false

for option in "$@"; do
    case "$option" in
        --real-data) use_real_data=true ;;
        --no-build|--trace-scroll) preview_options+=("$option"); has_preview_options=true ;;
        -h|--help)
            cat <<'EOF'
用法：./run-local-test.sh [--real-data] [--no-build] [--trace-scroll]

默认构建并启动当前工作目录的 Swing 版本，使用独立预览数据目录。
  --real-data  使用当前用户的 ~/.jlshell 数据和插件；测试期间的修改会写入该目录
  --no-build   使用最近一次已构建的预览包
  --trace-scroll  输出滚动事件间隔、绘制间隔和绘制耗时诊断，不记录终端内容

可通过 JLSHELL_PREVIEW_JAVA_HOME 指定 JDK 21，或通过
JLSHELL_PREVIEW_HOME 指定独立预览数据目录。
EOF
            exit 0 ;;
        *) printf '未知参数：%s\n使用 --help 查看用法。\n' "$option" >&2; exit 2 ;;
    esac
done

if [[ "$use_real_data" == true ]]; then
    export JLSHELL_PREVIEW_HOME="$HOME"
    printf '使用当前用户的 ~/.jlshell 数据和插件，测试中的修改会写入该目录。\n'
fi

if [[ "$has_preview_options" == true ]]; then
    exec "$project_dir/ui/run-preview.sh" "${preview_options[@]}"
fi
exec "$project_dir/ui/run-preview.sh"
