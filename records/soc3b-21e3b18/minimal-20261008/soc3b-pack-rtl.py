import pathlib, subprocess, shutil, json, hashlib, tarfile
repo=pathlib.Path('/home/cloud_chen/work/flow-soc3b-21e3b18')
rtl=repo/'design/build/rtl/axi-cluster/single/gshare/linux/production/cpu'
out=pathlib.Path('/home/cloud_chen/evidence/soc3b-21e3b18/rtl-snapshot')
out.mkdir(exist_ok=False)
manifest={'source_sha':subprocess.check_output(['git','-C',str(repo),'rev-parse','HEAD']).decode().strip(),'configuration':(rtl/'cluster-profile.txt').read_text(),'files':[],'includes':[]}
for line in (rtl/'filelist.f').read_text().splitlines():
 if not line.strip(): continue
 if line.startswith('+incdir+'):
  origin=pathlib.Path(line[len('+incdir+'):]); dest=out/('include-'+str(len(manifest['includes'])))
  shutil.copytree(origin,dest)
  manifest['includes'].append({'origin':str(origin),'relative':str(dest.relative_to(out)),'files':[{'relative':str(p.relative_to(out)),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(dest.rglob('*')) if p.is_file()]})
 else:
  origin=pathlib.Path(line); origin=origin if origin.is_absolute() else rtl/origin
  dest=out/('source-'+str(len(manifest['files'])))/origin.name
  dest.parent.mkdir(); shutil.copy2(origin,dest)
  manifest['files'].append({'origin':str(origin),'relative':str(dest.relative_to(out)),'sha256':hashlib.sha256(dest.read_bytes()).hexdigest()})
manifest['submodules']={str(p):subprocess.check_output(['git','-C',str(repo/p),'rev-parse','HEAD']).decode().strip() for p in map(pathlib.Path,['third_party/cvfpu','third_party/cvfpu/src/common_cells','third_party/cvfpu/src/fpu_div_sqrt_mvp'])}
(out/'source-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
shutil.copy2(rtl/'cluster-profile.txt',out/'cluster-profile.txt')
with tarfile.open(out.parent/'rtl-snapshot.tgz','w:gz') as tf: tf.add(out,arcname='rtl-snapshot')
print('packaged',len(manifest['files']),'sources',len(manifest['includes']),'include directories')
print('sha256',hashlib.sha256((out.parent/'rtl-snapshot.tgz').read_bytes()).hexdigest())
