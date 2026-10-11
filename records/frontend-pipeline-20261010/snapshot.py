#!/usr/bin/env python3
"""Capture exact source inputs for the isolated frontend gate, not a build."""
import hashlib, json, pathlib, subprocess, tarfile

repo = pathlib.Path(__file__).resolve().parents[2]
out = pathlib.Path(__file__).resolve().parent
tracked = subprocess.check_output(['git', 'ls-files', 'design', 'config'], cwd=repo, text=True).splitlines()
files = set(tracked)
for root in ['design/src/main/scala/frontend', 'design/src/test/scala/frontend']:
    files.update(str(p.relative_to(repo)) for p in (repo / root).rglob('*.scala'))
files.update(['docs/frontend-pipeline-design.md', 'docs/breeze-mmu-rtl-spec.md', 'agent.md'])
entries = []
with tarfile.open(out / 'source.tgz', 'w:gz') as archive:
    for name in sorted(files):
        path = repo / name
        if path.is_file():
            data = path.read_bytes()
            entries.append({'path': name, 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)})
            archive.add(path, arcname=name)
digest = hashlib.sha256(json.dumps(entries, sort_keys=True).encode()).hexdigest()
manifest = {'base_sha': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(),
            'source_digest': digest, 'files': entries,
            'submodules': subprocess.check_output(['git', 'submodule', 'status'], cwd=repo, text=True).splitlines()}
(out / 'source-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps({'base_sha': manifest['base_sha'], 'source_digest': digest, 'files': len(entries)}))
