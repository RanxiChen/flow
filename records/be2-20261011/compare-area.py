"""Compare matching hierarchical Vivado utilization stages; exclude CVFPU impl."""
import argparse,json,re
from pathlib import Path
ap=argparse.ArgumentParser(); ap.add_argument('baseline');ap.add_argument('candidate');ap.add_argument('output');args=ap.parse_args()
def rows(path):
    stack=[]; result={}
    for line in Path(path).read_text().splitlines():
        columns=line.split('|')
        if len(columns)<8 or not re.fullmatch(r'\d+',columns[3].strip()):continue
        label=columns[1];name=label.strip();depth=len(label)-len(label.lstrip())
        while stack and stack[-1][0]>=depth:stack.pop()
        key='/'.join([x[1] for x in stack]+[name])
        result[key]={'LUT':int(columns[3]),'FF':int(columns[7])};stack.append((depth,name))
    return result
baseline=rows(args.baseline);candidate=rows(args.candidate);result=[]
for key,values in baseline.items():
    if not re.search(r'/backend(_\d+)?$|/l1d(_\d+)?$',key):continue
    old=values.copy();new=candidate[key].copy();backend='/backend' in key
    if backend:
        for col in old:
            old[col]-=baseline[key+'/fpUnit/impl'][col]
            new[col]-=candidate[key+'/fpUnit/impl'][col]
    delta={col:100*(new[col]/old[col]-1) for col in old}
    result.append({'module':key,'baseline':old,'candidate':new,'delta_percent':delta,
                   'pass':delta['LUT'] <= (3 if backend else 2) and (not backend or delta['FF'] <= 10)})
assert len(result)==8,'expected four backend and four L1D rows'
Path(args.output).write_text(json.dumps(result,indent=2)+'\n')
print('A1', 'PASS' if all(r['pass'] for r in result) else 'FAIL')
