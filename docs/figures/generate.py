from pathlib import Path
from xml.sax.saxutils import escape
import xml.etree.ElementTree as E
P=Path(__file__).resolve().parent
class Diagram:
 def __init__(self,name,title,sub):
  self.name=name;self.items=[];self.edges=[]
  self.svg=['<svg xmlns="http://www.w3.org/2000/svg" width="1440" height="880" viewBox="0 0 1440 880"><defs><marker id="a" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0 0 L8 4 L0 8" fill="#64748b"/></marker></defs><rect width="1440" height="880" fill="#f8fafc"/>']
  self.box('title',48,34,1344,58,title,'#f8fafc',32,False)
  self.box('sub',48,98,1344,32,sub,'#f8fafc',16,False)
 def box(self,id,x,y,w,h,text,color='#ffffff',size=18,border=True):
  self.items.append((id,x,y,w,h,text,color,size,border))
  self.svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="12" fill="{color}" stroke="{"#cbd5e1" if border else color}"/>')
  lines=text.split('\n');start=y+h/2-(len(lines)-1)*size*.7
  for i,line in enumerate(lines): self.svg.append(f'<text x="{x+w/2}" y="{start+i*size*1.4}" text-anchor="middle" dominant-baseline="middle" fill="#0f172a" font-family="DejaVu Sans,Arial,sans-serif" font-size="{size}" font-weight="{600 if i==0 else 400}">{escape(line)}</text>')
 def edge(self,pts,dash=False):
  self.edges.append((pts,dash));self.svg.append('<polyline points="'+' '.join(f'{x},{y}' for x,y in pts)+f'" fill="none" stroke="#64748b" stroke-width="2" stroke-linejoin="round" marker-end="url(#a)"'+(' stroke-dasharray="7 5"' if dash else '')+'/>')
 def save(self):
  (P/(self.name+'.svg')).write_text('\n'.join(self.svg+['</svg>']))
  mx=E.Element('mxfile',host='app.diagrams.net');dia=E.SubElement(mx,'diagram',name=self.name);model=E.SubElement(dia,'mxGraphModel',page='1',pageWidth='1440',pageHeight='880');root=E.SubElement(model,'root');E.SubElement(root,'mxCell',id='0');E.SubElement(root,'mxCell',id='1',parent='0')
  for id,x,y,w,h,text,col,size,border in self.items:
   c=E.SubElement(root,'mxCell',id=id,value=text,style=f'rounded=1;whiteSpace=wrap;html=0;fillColor={col};strokeColor={"#cbd5e1" if border else "none"};fontColor=#0f172a;fontSize={size};fontFamily=Helvetica;',vertex='1',parent='1');E.SubElement(c,'mxGeometry',x=str(x),y=str(y),width=str(w),height=str(h),attrib={'as':'geometry'})
  for i,(pts,dash) in enumerate(self.edges):
   c=E.SubElement(root,'mxCell',id='edge'+str(i),style=f'endArrow=block;strokeColor=#64748b;strokeWidth=2;dashed={int(dash)};',edge='1',parent='1');g=E.SubElement(c,'mxGeometry',relative='1',attrib={'as':'geometry'})
   for (x,y),kind in [(pts[0],'sourcePoint'),(pts[-1],'targetPoint')]: E.SubElement(g,'mxPoint',x=str(x),y=str(y),attrib={'as':kind})
   a=E.SubElement(g,'Array',attrib={'as':'points'})
   for x,y in pts[1:-1]:E.SubElement(a,'mxPoint',x=str(x),y=str(y))
  E.indent(mx);E.ElementTree(mx).write(P/(self.name+'.drawio'),encoding='utf-8',xml_declaration=True)
d=Diagram('breeze-core','BREEZE / Blocking pipeline','IF → ID → EXE → MEM → WB  ·  Single issue / in order')
for id,x,w,title in [('if',50,175,'IF'),('id',275,175,'ID'),('ex',500,175,'EXE'),('mem',740,440,'MEM'),('wb',1240,150,'WB')]:
 d.box(id+'label',x,195,w,44,title,'#f8fafc',24,False)
d.box('fetch',50,305,175,100,'Fetch','#dbeafe',21)
d.box('decode',275,305,175,100,'Decode','#e0e7ff',21)
d.box('execute',500,305,175,100,'ALU / branch\nOperand preparation','#dcfce7',15)
d.box('memframe',740,265,440,210,'','#fff7df',18)
d.box('memtitle',760,278,400,35,'VARIABLE-LATENCY STAGE','#fff7df',16,False)
d.box('request',765,330,100,55,'Request','#ffffff',17)
d.box('wait',895,330,120,55,'Wait','#fde68a',19)
d.box('complete',1045,330,110,55,'Complete','#ffffff',17)
d.box('write',1240,305,150,100,'Writeback','#ede9fe',20)
for a,b in [(225,275),(450,500),(675,765)]:d.edge([(a,355),(b,355)])
d.edge([(865,357),(895,357)]);d.edge([(1015,357),(1045,357)])
d.edge([(1155,355),(1240,355)])
d.edge([(990,385),(990,419),(923,419),(923,385)],True)
d.box('waitlabel',840,438,325,26,'Hold MEM until completion','#fff7df',14,False)
# Requests descend to the selected unit; completion returns on a separate bus.
d.edge([(815,385),(815,505),(810,505),(810,555)])
for x in [945,1080,1215]:d.edge([(815,505),(x,505),(x,555)])
for id,x,t,col in [('mul',750,'MUL','#dcfce7'),('div',885,'DIV','#dcfce7'),('fpu',1020,'FPU','#dcfce7'),('cache',1155,'D-cache','#dbeafe')]:
 d.box(id,x,555,120,70,t,col,19)
 d.edge([(x+60,625),(x+60,675),(1290,675),(1290,425),(1100,425),(1100,385)])
d.box('reqLabel',900,475,230,25,'Request','#f8fafc',14,False)
d.box('rspLabel',865,687,360,30,'Result / completion','#f8fafc',15,False)
d.box('note',65,785,1310,55,'Multi-cycle MEM · Blocking request / response','#f8fafc',15,False)
d.save()
