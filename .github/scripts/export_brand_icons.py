#!/usr/bin/env python3
"""Export one approved brand master to desktop assets (macOS sips, standard-library packaging).

This script resizes/encapsulates artwork only. It never generates or retouches a design.
Usage: python3 .github/scripts/export_brand_icons.py /path/to/approved.png
"""
import base64
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
MASTER = ROOT / 'ui/src/main/resources/icons/brand/jlshell.png'
ICONS = ROOT / 'app/src/main/resources/icons'


def resize(source, destination, size):
    subprocess.run(['sips', '-z', str(size), str(size), str(source), '--out', str(destination)],
                   check=True, stdout=subprocess.DEVNULL)


def export(source):
    if not shutil.which('sips'):
        raise SystemExit('Export requires macOS sips; CI platform packaging continues to use its existing PNG converters.')
    MASTER.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='jlshell-brand-') as directory:
        temporary = Path(directory)
        normalized = temporary / 'master.png'
        resize(source, normalized, 1024)
        shutil.copyfile(normalized, MASTER)
        sizes = {}
        for size in [16, 24, 32, 48, 64, 128, 256, 512, 1024]:
            file = temporary / f'{size}.png'
            resize(MASTER, file, size)
            sizes[size] = file.read_bytes()
        for name in ['app_icon.png', 'jlshell_app_icon.png', 'jlshell_app_icon_clean.png']:
            shutil.copyfile(MASTER, ICONS / name)
        for size in [16, 32, 48, 256]:
            (ICONS / f'app_icon_{size}.png').write_bytes(sizes[size])
        (ICONS / 'jlshell_app_icon_512.png').write_bytes(sizes[512])
        ico_sizes = [16, 24, 32, 48, 64, 128, 256]
        entries, offset = [], 6 + 16 * len(ico_sizes)
        for size in ico_sizes:
            data = sizes[size]
            entries.append(struct.pack('<BBBBHHII', size % 256, size % 256, 0, 0, 1, 32, len(data), offset))
            offset += len(data)
        (ICONS / 'app_icon.ico').write_bytes(struct.pack('<HHH', 0, 1, len(ico_sizes))
                                           + b''.join(entries) + b''.join(sizes[size] for size in ico_sizes))
        icns_sizes = [('icp4', 16), ('icp5', 32), ('ic07', 128), ('ic08', 256), ('ic09', 512),
                      ('ic10', 1024), ('ic11', 32), ('ic12', 64), ('ic13', 256), ('ic14', 512)]
        body = b''.join(kind.encode('ascii') + struct.pack('>I', len(sizes[size]) + 8) + sizes[size]
                        for kind, size in icns_sizes)
        (ICONS / 'app_icon.icns').write_bytes(b'icns' + struct.pack('>I', len(body) + 8) + body)
        data = base64.b64encode(MASTER.read_bytes()).decode('ascii')
        (ICONS / 'jlshell_app_icon.svg').write_text(
            '<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">'
            f'<image width="1024" height="1024" href="data:image/png;base64,{data}"/></svg>\n', encoding='utf-8')
    print('Exported PNG sizes, ICO, ICNS and SVG wrapper from', MASTER)


if __name__ == '__main__':
    export(Path(sys.argv[1]).expanduser().resolve() if len(sys.argv) > 1 else MASTER)
