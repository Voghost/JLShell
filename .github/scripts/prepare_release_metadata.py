#!/usr/bin/env python3
"""Validate release notes and prepare public metadata plus an internal change audit."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import textwrap
from pathlib import Path


SEMVER_PATTERN = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
TAG_PATTERN = re.compile(r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
MAX_AUDITED_COMMITS = 100
CONVENTIONAL_TYPE_PATTERN = re.compile(r"^(?P<type>feat|fix|perf|refactor|docs|test|build|ci|chore)(?:\([^)]*\))?!?:")
CHANGE_TYPE_LABELS = {
    "feat": "新功能",
    "fix": "修复",
    "perf": "性能优化",
    "refactor": "代码优化",
    "docs": "文档",
    "test": "测试",
    "build": "构建",
    "ci": "CI",
    "chore": "维护",
    "other": "其他",
}


def fail(message: str) -> None:
    raise SystemExit(message)


def git(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout.strip()


def write(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")


def release_note_parts(source: Path, version: str) -> tuple[str, str, str, str]:
    content = source.read_text(encoding="utf-8-sig").strip()
    if not content:
        fail(f"{source} must not be empty.")

    lines = content.splitlines()
    expected_title = f"# JLShell v{version}"
    if lines[0].strip() != expected_title:
        fail(f"{source} must start with '{expected_title}'.")

    summary_index = next((index for index in range(1, len(lines)) if lines[index].strip()), None)
    if summary_index is None or not lines[summary_index].startswith("> "):
        fail(f"{source} must place a one-line summary starting with '> ' immediately after the title.")
    summary = lines[summary_index].removeprefix("> ").strip()
    if not summary:
        fail(f"{source} summary must not be empty.")

    release_notes = "\n".join(lines[summary_index + 1:]).strip()
    if not re.search(r"^##\s+\S+", release_notes, re.MULTILINE):
        fail(f"{source} must contain at least one level-two section.")
    if not re.search(r"^[-*+]\s+\S+", release_notes, re.MULTILINE):
        fail(f"{source} must contain at least one bullet item.")

    return content, expected_title, summary, release_notes


def previous_release_tag(version: str) -> str | None:
    requested = os.environ.get("PREVIOUS_RELEASE_TAG", "").strip()
    if requested:
        if not TAG_PATTERN.fullmatch(requested):
            fail("PREVIOUS_RELEASE_TAG must use the vMAJOR.MINOR.PATCH form.")
        git("rev-parse", "--verify", "--quiet", f"{requested}^{{commit}}")
        return requested

    current_tag = f"v{version}"
    tags = git("tag", "--merged", "HEAD", "--sort=-version:refname").splitlines()
    return next((tag for tag in tags if tag != current_tag and TAG_PATTERN.fullmatch(tag)), None)


def change_audit(version: str) -> tuple[str, dict[str, object]]:
    previous_tag = previous_release_tag(version)
    if previous_tag:
        commit_range = f"{previous_tag}..HEAD"
        commit_count = int(git("rev-list", "--count", commit_range))
        commit_lines = git(
            "log", "--format=- %s (`%h`)", f"--max-count={MAX_AUDITED_COMMITS}", commit_range
        ).splitlines()
        changed_files = git("diff", "--name-only", commit_range).splitlines()
        file_stat = git("diff", "--stat", commit_range)
        baseline = previous_tag
    else:
        commit_range = "HEAD (initial release snapshot)"
        commit_count = int(git("rev-list", "--count", "HEAD"))
        commit_lines = git(
            "log", "--format=- %s (`%h`)", f"--max-count={MAX_AUDITED_COMMITS}", "HEAD"
        ).splitlines()
        changed_files = git("ls-tree", "-r", "--name-only", "HEAD").splitlines()
        file_stat = f"Initial snapshot: {len(changed_files)} tracked files."
        baseline = "none (initial release)"

    all_subjects = git("log", "--format=%s", commit_range if previous_tag else "HEAD").splitlines()
    type_counts = {key: 0 for key in CHANGE_TYPE_LABELS}
    for subject in all_subjects:
        match = CONVENTIONAL_TYPE_PATTERN.match(subject.strip())
        type_counts[match.group("type") if match else "other"] += 1
    type_rows = "\n".join(
        f"| {CHANGE_TYPE_LABELS[key]} | {count} |"
        for key, count in type_counts.items()
        if count
    ) or "| 其他 | 0 |"

    if commit_count > MAX_AUDITED_COMMITS:
        commit_lines.append(f"- … truncated after {MAX_AUDITED_COMMITS} commits")

    commit_section = "\n".join(commit_lines) if commit_lines else "- No commits found."
    audit = (
        f"# JLShell v{version} 变更审计\n\n"
        f"- 对比基线：`{baseline}`\n"
        f"- 提交范围：`{commit_range}`\n"
        f"- 提交数量：{commit_count}\n"
        f"- 变更文件：{len(changed_files)} 个\n\n"
        f"## 变更类型统计\n\n| 类型 | 提交数 |\n|---|---:|\n{type_rows}\n\n"
        f"## 提交摘要\n\n{commit_section}\n\n"
        f"## 文件统计\n\n```text\n{file_stat or 'No file changes found.'}\n```\n"
    )
    context = {
        "previousTag": previous_tag or "",
        "commitRange": commit_range,
        "commitCount": commit_count,
        "changedFileCount": len(changed_files),
    }
    return audit, context


def main() -> None:
    version = os.environ.get("VERSION", "").strip()
    if not SEMVER_PATTERN.fullmatch(version):
        fail("VERSION must use the MAJOR.MINOR.PATCH form without a leading 'v'.")

    notes_dir = Path(os.environ.get("RELEASE_NOTES_DIR", ".github/release-notes"))
    source = notes_dir / f"{version}.md"
    if not source.is_file():
        fail(f"Missing {source}. Copy {notes_dir}/TEMPLATE.md and document this release before packaging.")

    output_dir = Path(os.environ.get("RELEASE_OUTPUT_DIR", "."))
    output_dir.mkdir(parents=True, exist_ok=True)
    content, title_line, summary, release_notes = release_note_parts(source, version)
    audit, audit_context = change_audit(version)
    repository = os.environ.get("GITHUB_REPOSITORY", "Voghost/JLShell")
    release_url = f"https://github.com/{repository}/releases/tag/v{version}"

    metadata = {
        "title": title_line.removeprefix("# "),
        "summary": summary,
        "releaseNotes": release_notes,
        "releaseUrl": release_url,
        "changeAudit": audit_context,
    }
    write(output_dir / "release-metadata.json", json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    write(output_dir / "release-notes.md", release_notes + "\n")
    write(output_dir / "release-change-summary.md", audit)

    downloads = textwrap.dedent(
        f"""

        ## 下载

        | 平台 | 文件 | 说明 |
        |------|------|------|
        | macOS | `JLShell-{version}-mac.zip` | 解压后双击 `JLShell.app` |
        | Linux | `JLShell-{version}-linux-x64.tar.gz` | 解压后运行 `./JLShell.sh` |
        | Linux | `JLShell-{version}-linux-x64.deb` | `sudo dpkg -i` 安装，命令行 `jlshell` |
        | Linux | `JLShell-{version}-linux-x86_64.AppImage` | `chmod +x` 后直接运行 |
        | Windows | `JLShell-{version}-win-x64.zip` | 解压后双击 `JLShell.exe` |
        | Windows | `JLShell-{version}-win-x64.msi` | 安装后可从开始菜单搜索 `JLShell` |
        | Incremental | `jlshell-app-{version}.jar` | 官网更新 API 使用的 jar 增量包 |

        > 所有完整安装包均内置精简 JRE，无需安装 JDK。
        """
    )
    write(output_dir / "github-release-body.md", content + downloads)

    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        workflow_outputs = {
            "previous_tag": audit_context["previousTag"] or "none",
            "commit_range": audit_context["commitRange"],
            "commit_count": audit_context["commitCount"],
        }
        with Path(github_output).open("a", encoding="utf-8") as output:
            for key, value in workflow_outputs.items():
                output.write(f"{key}={value}\n")

    print(f"Validated release notes: {source}")
    print(f"Change audit: {audit_context['commitRange']} ({audit_context['commitCount']} commits)")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        stderr = error.stderr.strip()
        fail(f"Git command failed: {' '.join(error.cmd)}{': ' + stderr if stderr else ''}")
