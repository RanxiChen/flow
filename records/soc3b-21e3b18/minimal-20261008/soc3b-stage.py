import subprocess, pathlib, json, time, datetime, sys, shutil, os, signal
stage, shell_command = sys.argv[1:]
repo=pathlib.Path('/home/cloud_chen/work/flow-soc3b-21e3b18')
evidence=pathlib.Path('/home/cloud_chen/evidence/soc3b-21e3b18')/stage
evidence.mkdir(parents=True,exist_ok=False)
sha=subprocess.check_output(['git','-C',str(repo),'rev-parse','HEAD']).decode().strip()
meta={'source_sha':sha,'host':subprocess.check_output(['hostname']).decode().strip(),'ssh_target':'cloud_chen@47.96.71.231','cwd':str(repo/'design'),'command':shell_command,'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'submodule_cvfpu':subprocess.check_output(['git','-C',str(repo/'third_party/cvfpu'),'rev-parse','HEAD']).decode().strip()}
(evidence/'command.txt').write_text(shell_command+'\n')
(evidence/'running.json').write_text(json.dumps(meta,indent=2)+'\n')
t=time.monotonic()
with (evidence/'run.log').open('w') as log:
 p=subprocess.Popen(['bash','-lc','source /home/cloud_chen/setup/activate-flow.sh\n'+shell_command],cwd=repo/'design',stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
 try: code=p.wait(timeout=1800)
 except subprocess.TimeoutExpired:
  os.killpg(p.pid,signal.SIGTERM)
  try: p.wait(timeout=15)
  except subprocess.TimeoutExpired: os.killpg(p.pid,signal.SIGKILL); p.wait()
  code=124
meta.update(exit_code=code,elapsed_seconds=time.monotonic()-t,ended_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
(evidence/'exit').write_text(str(code)+'\n')
for f in (repo/'design/target/test-reports').glob('*.xml'):
 if f.stat().st_mtime>=time.time()-meta['elapsed_seconds']-5:
  (evidence/'xml').mkdir(exist_ok=True); shutil.copy2(f,evidence/'xml'/f.name)
(evidence/'result.json').write_text(json.dumps(meta,indent=2)+'\n')
print(json.dumps(meta,indent=2),flush=True)
sys.exit(0 if code==0 else 1)
