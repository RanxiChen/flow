import pathlib, subprocess, json, time, datetime, hashlib, os
root=pathlib.Path('/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc')
manifest=json.loads((root/'rtl-snapshot/source-manifest.json').read_text())
for entry in manifest['files']:
 p=root/'rtl-snapshot'/entry['relative']
 assert hashlib.sha256(p.read_bytes()).hexdigest()==entry['sha256'],p
for include in manifest['includes']:
 for entry in include['files']:
  p=root/'rtl-snapshot'/entry['relative']; assert hashlib.sha256(p.read_bytes()).hexdigest()==entry['sha256'],p
cmd=['timeout','--signal=TERM','--kill-after=15s','30m','nice','-n','10','/home/chen/Tool/FPGA/Vivado/2022.2/bin/vivado','-mode','batch','-source',str(root/'synth.tcl'),'-log',str(root/'vivado.log'),'-journal',str(root/'vivado.jou')]
meta={'source_sha':manifest['source_sha'],'host':'Alan','hostname':subprocess.check_output(['hostname']).decode().strip(),'cwd':str(root),'command':cmd,'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'source_manifest_sha256':hashlib.sha256((root/'rtl-snapshot/source-manifest.json').read_bytes()).hexdigest(),'configuration':manifest['configuration']}
(root/'command.json').write_text(json.dumps(meta,indent=2)+'\n'); started=time.monotonic()
with (root/'ooc.log').open('w') as out:
 p=subprocess.Popen(cmd,cwd=root,stdout=out,stderr=subprocess.STDOUT,start_new_session=True)
 (root/'vivado.pid').write_text(str(p.pid)+'\n'); code=p.wait()
meta.update(exit_code=code,elapsed_seconds=time.monotonic()-started,ended_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
(root/'exit').write_text(str(code)+'\n'); (root/'result.json').write_text(json.dumps(meta,indent=2)+'\n')
print(json.dumps(meta,indent=2),flush=True)
