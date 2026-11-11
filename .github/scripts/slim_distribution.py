#!/usr/bin/env python3
"""Curate supported native targets for the Swing-only application.

Never use reachability-based class minimization: JDBC/JNA/SSH/plugin services load dynamically.
"""
import argparse
import json
import platform
import zipfile
from pathlib import Path

TARGETS = {
    'linux-x64': ('Linux/x86_64/', 'linux-x86-64/', 'linux/x86-64/', 'linux-x86_64'),
    'win-x64': ('Windows/x86_64/', 'win32-x86-64/', 'win/x86-64/', 'windows-x86_64'),
    'mac-x64': ('Mac/x86_64/', 'darwin-x86-64/', 'darwin/', 'macos-x86_64'),
    'mac-arm64': ('Mac/aarch64/', 'darwin-aarch64/', 'darwin/', 'macos-arm64'),
}
# Build-time icons and historical duplicate masters are not runtime resources.
BUILD_ONLY = {'icons/app_icon.png', 'icons/app_icon.ico', 'icons/app_icon.icns', 'icons/jlshell_app_icon.png',
              'icons/jlshell_app_icon_clean.png', 'icons/jlshell_app_icon.svg', 'icons/jlshell_app_icon_512.png'}


def keep_native(name, targets):
    prefixes = ('org/sqlite/native/', 'com/sun/jna/', 'resources/com/pty4j/native/', 'com/formdev/flatlaf/natives/')
    for index, prefix in enumerate(prefixes):
        if not name.startswith(prefix) or not name.endswith(('.so', '.dll', '.exe', '.dylib', '.jnilib', '.a', '/pty4j-unix-spawn-helper')):
            continue
        return any(TARGETS[target][index] in name for target in targets)
    return True


def slim(source, output, target='universal'):
    targets = list(TARGETS) if target == 'universal' else [target]
    output.parent.mkdir(parents=True, exist_ok=True)
    dropped = []
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as result:
        if any(name.startswith(('javafx/', 'com/sun/javafx/', 'com/sun/glass/', 'com/sun/prism/')) for name in original.namelist()):
            raise ValueError('Unexpected JavaFX entries in the Swing-only application payload')
        for item in original.infolist():
            if item.is_dir():
                continue
            if item.filename in BUILD_ONLY or not keep_native(item.filename, targets):
                dropped.append(item.filename)
                continue
            result.writestr(item, original.read(item), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    with zipfile.ZipFile(output) as result:
        names = set(result.namelist())
        required = {'META-INF/MANIFEST.MF', 'icons/brand/jlshell.png',
                    'META-INF/services/java.sql.Driver', 'com/jlshell/app/Launcher.class'}
        if missing := required - names:
            raise ValueError(f'Missing runtime entries: {missing}')
        for selected in targets:
            if not any(n.startswith('org/sqlite/native/' + TARGETS[selected][0]) for n in names):
                raise ValueError(f'Missing SQLite native for {selected}')
    return {'target': target, 'beforeBytes': source.stat().st_size, 'afterBytes': output.stat().st_size,
            'removedEntries': len(dropped), 'swingOnly': True}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('source', type=Path); p.add_argument('output', type=Path)
    p.add_argument('--target', choices=['universal', 'native', *TARGETS], default='universal')
    args = p.parse_args()
    target = args.target
    if target == 'native':
        target = {'Darwin': 'mac-', 'Windows': 'win-', 'Linux': 'linux-'}[platform.system()]
        target += 'arm64' if platform.machine().lower() in ('aarch64', 'arm64') else 'x64'
    if args.source.resolve() == args.output.resolve():
        p.error('source and output must differ')
    print(json.dumps(slim(args.source, args.output, target), indent=2))


if __name__ == '__main__':
    main()
