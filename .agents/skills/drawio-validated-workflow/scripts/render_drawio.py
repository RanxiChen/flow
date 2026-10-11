#!/usr/bin/env python3
import os
from pathlib import Path
import shutil
import subprocess
import sys

def main():
    if len(sys.argv) != 3:
        raise SystemExit('usage: render_drawio.py INPUT.drawio OUTPUT.svg')
    source = Path(sys.argv[1]).resolve()
    output = Path(sys.argv[2]).resolve()
    if not source.is_file():
        raise SystemExit('Input file does not exist')
    if output.exists():
        raise SystemExit('Refusing to overwrite preview')
    binary = os.environ.get('BREEZE_DRAWIO_BIN') or shutil.which('drawio') or shutil.which('draw.io')
    if not binary:
        raise SystemExit('Draw.io CLI unavailable; set BREEZE_DRAWIO_BIN to an installed CLI')
    source_arg, output_arg = str(source), str(output)
    if binary.lower().endswith('.exe') and sys.platform != 'win32':
        if not shutil.which('wslpath'):
            raise SystemExit('Windows CLI requires WSL path conversion')
        source_arg = subprocess.check_output(['wslpath', '-w', source_arg], text=True).strip()
        output_arg = subprocess.check_output(['wslpath', '-w', output_arg], text=True).strip()
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([binary, '--disable-gpu', '--export', '--format', 'svg', '--output', output_arg, source_arg], check=True)
    if not output.is_file() or not output.stat().st_size:
        raise SystemExit('Export did not produce a non-empty SVG')
    print(output)

if __name__ == '__main__':
    main()
