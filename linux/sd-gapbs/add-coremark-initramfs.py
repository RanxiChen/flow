#!/usr/bin/env python3
"""Append CoreMark to the unchanged GAPBS newc archive, then verify contents."""
import argparse
import hashlib
import json
from pathlib import Path
import stat

def digest(data):
    return hashlib.sha256(data).hexdigest()

def entries(data):
    offset = 0
    result = {}
    while offset < len(data):
        while offset < len(data) and data[offset] == 0:
            offset += 1
        if offset == len(data):
            break
        assert data[offset:offset + 6] in (b'070701', b'070702')
        fields = [int(data[offset + 6 + i * 8:offset + 14 + i * 8], 16) for i in range(13)]
        size, namesize = fields[6], fields[11]
        start = offset + 110
        name = data[start:start + namesize]
        assert namesize > 0 and name[-1] == 0
        start = (start + namesize + 3) & ~3
        assert start + size <= len(data)
        path = name[:-1].decode().removeprefix('./')
        assert '..' not in Path(path).parts and not path.startswith('/')
        if path != 'TRAILER!!!':
            result[path] = data[start:start + size]
        offset = (start + size + 3) & ~3
    return result

def newc(name, data, mode, inode):
    name = name.encode() + b'\0'
    fields = (inode, mode, 0, 0, 1, 0, len(data), 0, 0, 0, 0, len(name), 0)
    item = b'070701' + ''.join(f'{v:08x}' for v in fields).encode() + name
    item += b'\0' * (-len(item) % 4)
    item += data
    return item + b'\0' * (-len(item) % 4)

parser = argparse.ArgumentParser()
parser.add_argument('--base', required=True, type=Path)
parser.add_argument('--coremark', required=True, type=Path)
parser.add_argument('--license', required=True, type=Path)
parser.add_argument('--build-info', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
assert not args.output.exists()
base = args.base.read_bytes()
before = entries(base)
binary = args.coremark.read_bytes()
assert binary[:4] == b'\x7fELF' and int.from_bytes(binary[18:20], 'little') == 243
assert 'usr/bin/coremark' not in before
addition = b''
items = [('usr/bin/coremark', binary),
         ('usr/share/licenses/coremark/LICENSE.md', args.license.read_bytes()),
         ('usr/share/coremark/build-info.txt', args.build_info.read_bytes())]
for inode, directory in enumerate(('usr', 'usr/bin', 'usr/share', 'usr/share/licenses',
                                    'usr/share/licenses/coremark', 'usr/share/coremark'), 1):
    addition += newc(directory, b'', stat.S_IFDIR | 0o755, inode)
for inode, (name, content) in enumerate(items, 100):
    mode = 0o755 if name == 'usr/bin/coremark' else 0o644
    addition += newc(name, content, stat.S_IFREG | mode, inode)
addition += newc('TRAILER!!!', b'', 0, 200)
merged = base + b'\0' * (-len(base) % 4) + addition
merged += b'\0' * (-len(merged) % 512)
after = entries(merged)
for name, data in before.items():
    assert after[name] == data, f'Existing rootfs data changed: {name}'
for name, data in items:
    assert after[name] == data
gapbs = {}
for name in ('bc', 'bfs', 'cc', 'cc_sv', 'pr', 'pr_spmv', 'sssp', 'tc', 'converter'):
    path = 'opt/gapbs/' + name
    assert before[path][:4] == b'\x7fELF'
    gapbs[name] = digest(after[path])
assert 'usr/bin/gapbs-smoke' in after
args.output.write_bytes(merged)
proof = {'base_rootfs_sha256': digest(base), 'new_rootfs_sha256': digest(merged),
         'coremark_sha256': digest(binary), 'GAPBS_unchanged': gapbs,
         'existing_archive_entries_preserved': len(before),
         'added_files': [name for name, _ in items], 'target_runtime': 'NOT_RUN'}
args.output.with_suffix('.manifest.json').write_text(json.dumps(proof, indent=2) + '\n')
print(json.dumps(proof, indent=2))
