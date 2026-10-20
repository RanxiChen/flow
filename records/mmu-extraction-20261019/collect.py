import hashlib, json, shutil, sys, xml.etree.ElementTree as ET
from pathlib import Path
work, logs = map(Path, sys.argv[1:])
manifest=json.loads((logs/'source-manifest.json').read_text())
identity=[]
for name, digest in (manifest['overlay'] | manifest.get('supplemental_files',{})).items():
    actual=hashlib.sha256((work/name).read_bytes()).hexdigest()
    identity.append({'file':name,'expected':digest,'actual':actual,'match':actual==digest})
reports=logs/'test-reports'; reports.mkdir(exist_ok=True)
for p in (work/'design/target/test-reports').glob('TEST-*.xml'):
    shutil.copy2(p,reports/p.name)
suites=[]
for p in sorted(reports.glob('TEST-*.xml')):
    root=ET.parse(p).getroot()
    suites.append({'file':p.name,'name':root.attrib.get('name'),**{k:int(root.attrib.get(k,0)) for k in ('tests','errors','failures','skipped')}})
exits={p.name:p.read_text().strip() for p in logs.glob('*.exit')}
expected_suites = {'flow.top.MmuExtractionElabSpec', 'flow.frontend.BreezeFrontendPipelineSpec',
    'flow.frontend.BreezeResolutionReservationSpec', 'flow.memsys.L1IRegisteredReturnSpec',
    'flow.mmu.sv39.Sv39MmuSpec', 'flow.mmu.sv39.Sv39PmpCandidateSpec',
    'flow.cluster.ClusterProgramSpec', 'flow.cluster.ZveSystemSmokeSpec', 'flow.cluster.RvvSystemSpec'}
complete = {s['name'] for s in suites} == expected_suites and set(exits) == {'compile.exit','focused.exit','system.exit'}
result={'complete':complete,'source_matches' :all(x['match'] for x in identity),'source_files':identity,'exits':exits,'suites':suites,'total':{k:sum(s[k] for s in suites) for k in ('tests','errors','failures','skipped')}}
(logs/'result.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if k!='source_files'},indent=2))
if not complete or not result['source_matches'] or any(v!='0' for v in exits.values()) or any(result['total'][k] for k in ('errors','failures','skipped')):
    sys.exit(1)
