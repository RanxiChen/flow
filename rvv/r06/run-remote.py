#!/usr/bin/env python3
"""Select the live configured host, then run a SHA-bound isolated R06 gate."""
import argparse
import datetime
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
CONFIG=Path('/home/chen/leisure/flow/docs/cross-project/simulation-host.md')
ap=argparse.ArgumentParser()
ap.add_argument('stage')
ap.add_argument('suites',nargs='+')
ap.add_argument('--submodules',action='store_true')
ap.add_argument('--rtl',action='store_true',help='emit the production top before the selected suites')
args=ap.parse_args()
if not re.fullmatch(r'[a-z0-9-]+',args.stage):
    ap.error('stage must be a safe path component')
if any(not re.fullmatch(r'flow\.[A-Za-z0-9_.]+',s) for s in args.suites):
    ap.error('only explicit Flow test suites are accepted')
sha=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
if subprocess.check_output(['git','diff','HEAD','--name-only'],cwd=ROOT,text=True).strip():
    sys.exit('Tracked source changes must be committed before a SHA-bound gate')
if subprocess.check_output(['git','ls-files','--others','--exclude-standard','--','design/src','rvv/r06'],cwd=ROOT,text=True).strip():
    sys.exit('New source files must be committed before a SHA-bound gate')
raw=CONFIG.read_text()
blocks=re.findall(r'```text\n(.*?)```',raw,re.S)
hosts=[]
for b in blocks:
    h=dict(re.findall(r'^([a-z_]+)\s*=\s*(.*)$',b,re.M))
    if 'ssh_target' in h:
        hosts.append(h)
stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
local=ROOT/'records'/'r06'/f'{sha[:8]}-{args.stage}-{stamp}'
local.mkdir(parents=True)
(local/'simulation-host.md').write_text(raw)
failures=[]
chosen=None
for h in hosts:
    ssh=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=8','-p',h['ssh_port']]
    if h.get('ssh_jump'):
        ssh+=['-J',h['ssh_jump']]
    ssh+=[h['ssh_target']]
    probe=(h['breeze_rvv_environment']+'; set -e; hostname; java -version; verilator --version; '
      'test -f /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar; '
      'free -h; df -h '+shlex.quote(h['workspace_root'])) if h.get('ssh_jump') else (
      h['breeze_rvv_environment']+'; set -e; hostname; command -v sbt; java -version; verilator --version; '
      'free -h; df -h '+shlex.quote(h['workspace_root']))
    try:
        result=subprocess.run(ssh+[probe],capture_output=True,text=True,timeout=30)
    except subprocess.TimeoutExpired as e:
        failures.append({'host':h['ssh_target'],'reason':'preflight timeout','exit':None})
        continue
    (local/f'preflight-{len(failures)}.log').write_text(result.stdout+result.stderr)
    if result.returncode:
        failures.append({'host':h['ssh_target'],'exit':result.returncode,'reason':result.stdout+result.stderr})
        continue
    chosen=(h,ssh)
    break
if chosen is None:
    (local/'preflight-failures.json').write_text(json.dumps(failures,indent=2))
    sys.exit('Both configured hosts unavailable: '+json.dumps(failures))
h,ssh=chosen
remoteRoot=h['workspace_root']+'/flow-rvv-zve32f-20261012'
evidence=h['evidence_root'].split()[0]+'/rvv-r06/'+local.name
launcher='java -XX:ActiveProcessorCount=2 -Xmx6G -jar /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar' if h.get('ssh_jump') else 'sbt'
test='testOnly '+' '.join(args.suites)
command='JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 MAKEFLAGS=-j2 taskset -c 8-9 nice -n 10 '+launcher+' '+shlex.quote('set Test / parallelExecution := false')+' '+shlex.quote(test)
rtlCommand='JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 taskset -c 8-9 nice -n 10 '+launcher+' '+shlex.quote('runMain flow.rvv.zve.GenerateZve '+evidence+'/rtl')
# Cloud core IDs are not assumed to match Alan. Two processor threads remain bounded.
if not h.get('ssh_jump'):
    command=command.replace('taskset -c 8-9 ','')
    rtlCommand=rtlCommand.replace('taskset -c 8-9 ','')
script=f'''set -e
{h['breeze_rvv_environment']}
mkdir -p {shlex.quote(evidence)}
curl -fsSI --connect-timeout 8 --max-time 20 --proxy {shlex.quote(h['proxy_url'])} https://repo.maven.apache.org/maven2/ > {shlex.quote(evidence)}/proxy-preflight.txt
if test ! -d {shlex.quote(remoteRoot)}/.git; then
  git -c http.proxy={shlex.quote(h['proxy_url'])} clone --no-checkout https://github.com/RanxiChen/flow.git {shlex.quote(remoteRoot)}
fi
test -z "$(git -C {shlex.quote(remoteRoot)} diff HEAD --name-only)"
git -C {shlex.quote(remoteRoot)} -c http.proxy={shlex.quote(h['proxy_url'])} fetch origin feat/rvv-zve32f-coprocessor-20261012
git -C {shlex.quote(remoteRoot)} checkout --detach {sha}
test "$(git -C {shlex.quote(remoteRoot)} rev-parse HEAD)" = {sha}
'''
if args.submodules:
    script+=f'git -C {shlex.quote(remoteRoot)} -c http.proxy={shlex.quote(h["proxy_url"])} submodule update --init --recursive\n'
generation=''
if args.rtl:
    generation=f'''printf '%s\\n' {shlex.quote(rtlCommand)} > {shlex.quote(evidence)}/rtl-command.txt
{rtlCommand} > {shlex.quote(evidence)}/rtl.log 2>&1
rtl_rc=$?
printf '%s\\n' "$rtl_rc" > {shlex.quote(evidence)}/rtl-exit-code.txt
if test "$rtl_rc" != 0; then
  printf '%s\\n' "$rtl_rc" > {shlex.quote(evidence)}/exit-code.txt
  tail -55 {shlex.quote(evidence)}/rtl.log
  exit "$rtl_rc"
fi
find {shlex.quote(evidence)}/rtl -type f -exec sha256sum {{}} + > {shlex.quote(evidence)}/rtl-sha256.txt
'''
script+=f'''cd {shlex.quote(remoteRoot)}/design
git rev-parse HEAD > {shlex.quote(evidence)}/sha.txt
git submodule status --recursive > {shlex.quote(evidence)}/submodules.txt
hostname > {shlex.quote(evidence)}/host.txt
pwd > {shlex.quote(evidence)}/cwd.txt
printf '%s\\n' {shlex.quote(command)} > {shlex.quote(evidence)}/command.txt
java -version > {shlex.quote(evidence)}/java.txt 2>&1
verilator --version > {shlex.quote(evidence)}/verilator.txt
set +e
{generation}
touch {shlex.quote(evidence)}/test-start-marker
{command} > {shlex.quote(evidence)}/test.log 2>&1
rc=$?
printf '%s\\n' "$rc" > {shlex.quote(evidence)}/exit-code.txt
mkdir -p {shlex.quote(evidence)}/xml
find target/test-reports -maxdepth 1 -name '*.xml' -newer {shlex.quote(evidence)}/test-start-marker -exec cp {{}} {shlex.quote(evidence)}/xml/ \\;
tail -55 {shlex.quote(evidence)}/test.log
exit "$rc"
'''
(local/'run.sh').write_text(script)
provenance={'sha':sha,'host':h['ssh_target'],'cwd':remoteRoot+'/design','evidence':evidence,
            'suites':args.suites,'preflight_failures':failures,'command':command}
if args.rtl:
    provenance['rtl_command']=rtlCommand
(local/'provenance.json').write_text(json.dumps(provenance,indent=2))
print(json.dumps(provenance),flush=True)
result=subprocess.run(ssh+['bash','-s'],input=script,text=True)
provenance['exit']=result.returncode
(local/'provenance.json').write_text(json.dumps(provenance,indent=2))
scp=['scp','-q','-r','-P',h['ssh_port']]
if h.get('ssh_jump'):
    scp+=['-o','ProxyJump='+h['ssh_jump']]
copy=subprocess.run(scp+[h['ssh_target']+':'+evidence,str(local/'remote')])
if copy.returncode:
    print('Evidence copy failed; remote evidence retained',file=sys.stderr)
print('LOCAL_EVIDENCE='+str(local),flush=True)
sys.exit(result.returncode or copy.returncode)
