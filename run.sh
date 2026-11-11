#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export JLSHELL_PREVIEW_MODE=false
exec "$project_dir/run-local-test.sh" --real-data "$@"
