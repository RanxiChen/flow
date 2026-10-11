"""Prune only stale task simulator object caches; retain all evidence and binaries."""
import hashlib, json, os, pathlib, time, sys
allowed={'flow-be2-b3-8ea5db5','flow-be2-1a4deba','flow-be2-r6-handoff-8e5bd3b'}
name=sys.argv[1] if len(sys.argv)>1 else 'flow-be2-b3-8ea5db5'
assert name in allowed
cache='cluster-sim' if '--cluster' in sys.argv[2:] else 'chiselsim'
root=(pathlib.Path('/home/chen/FUN')/name/'design/build'/cache).resolve()
manifest=pathlib.Path('/home/chen/FUN/flow-runs/be2-closed-object-prune.jsonl')
active=[]
for proc in pathlib.Path('/proc').iterdir():
 if not proc.name.isdigit(): continue
 try:
  cwd=(proc/'cwd').resolve(strict=True)
  if root in cwd.parents or cwd==root: active.append(cwd)
 except (OSError, RuntimeError): pass
closed='--closed' in sys.argv[2:]
if closed:
 assert not active,active
 for proc in pathlib.Path('/proc').iterdir():
  if not proc.name.isdigit():continue
  try:
   cwd=(proc/'cwd').resolve(strict=True)
   cmd=(proc/'cmdline').read_bytes()
   if cwd==root.parent.parent or root.parent.parent in cwd.parents:
    assert b'java' not in cmd and b'verilator' not in cmd and b'cc1plus' not in cmd,(proc,cwd,cmd)
  except (OSError,RuntimeError):pass
removed=0; freed=0
with manifest.open('a') as out:
 out.write(json.dumps({'root':str(root),'time':time.time(),'active_cwds':[str(p) for p in active]})+'\n');out.flush()
 for p in root.rglob('*'):
  if p.suffix not in {'.o','.a','.gch'} or p.is_symlink() or not p.is_file():continue
  st=p.stat()
  if (not closed and time.time()-st.st_mtime<900) or st.st_nlink!=1:continue
  work=next((q for q in p.parents if q.name=='workdir-verilator'),None)
  if work is None or any(c==work or work in c.parents for c in active):continue
  digest=hashlib.sha256(p.read_bytes()).hexdigest()
  if p.stat().st_mtime_ns!=st.st_mtime_ns:continue
  out.write(json.dumps({'path':str(p),'size':st.st_size,'sha256':digest})+'\n');out.flush()
  p.unlink();removed+=1;freed+=st.st_blocks*512
 out.write(json.dumps({'removed':removed,'allocated_bytes_freed':freed})+'\n')
print(json.dumps({'removed':removed,'allocated_bytes_freed':freed,'manifest':str(manifest)}))
