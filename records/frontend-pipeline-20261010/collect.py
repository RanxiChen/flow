#!/usr/bin/env python3
"""Package completed gate evidence; retain large checkpoints on Alan."""
import hashlib, json, pathlib, sys, tarfile

root = pathlib.Path('/home/chen/FUN/flow-runs/frontend-pipeline-20261010')
for name in sys.argv[1:]:
    assert name.replace('-', '').isalnum(), name
    stage = root / name
    assert (stage / 'result.json').exists(), f'{name} is not complete'
    checkpoints = []
    for file in sorted(stage.glob('*.dcp')):
        checkpoints.append({'path': str(file), 'bytes': file.stat().st_size,
                            'sha256': hashlib.sha256(file.read_bytes()).hexdigest()})
    (stage / 'checkpoints.json').write_text(json.dumps(checkpoints, indent=2) + '\n')
    files = [p for p in stage.iterdir() if p.is_file() and
             (p.suffix in {'.rpt', '.log', '.json', '.tcl', '.xdc', '.jou', '.csv', '.sv'} or p.name == 'log.txt')]
    for directory in ['rtl', 'test-reports']:
        if (stage / directory).exists():
            files.extend(p for p in (stage / directory).rglob('*') if p.is_file())
    entries = [{'path': str(p.relative_to(root)), 'bytes': p.stat().st_size,
                'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(files)]
    exports = root / 'exports'
    exports.mkdir(exist_ok=True)
    with tarfile.open(exports / f'{name}.tgz', 'w:gz') as archive:
        for file in sorted(files):
            archive.add(file, arcname=str(file.relative_to(root)))
    (exports / f'{name}-manifest.json').write_text(json.dumps(entries, indent=2) + '\n')
    print(json.dumps({'name': name, 'files': len(files), 'checkpoints_on_Alan': checkpoints}))
