"""Generate the parameter overview without changing the pipeline/system figures."""
from pathlib import Path
helper=Path(__file__).with_name('generate.py')
exec(compile(helper.read_text().split("d=Diagram('breeze-core'")[0],str(helper),'exec'))
d=Diagram('breeze-organization','BREEZE / Core organization','RV64IMAFDC + Zicsr + Zifencei  ·  64-bit  ·  Single issue / in order  ·  100 MHz FPGA baseline')
# Background groups precede their contents.
for id,x,w,title,color in [('front',40,350,'FRONTEND','#dbeafe'),('back',430,570,'BACKEND','#dcfce7'),('memory',1040,360,'MEMORY & PRIVILEGE','#fef3c7')]:
 d.box(id+'panel',x,175,w,570,'','#ffffff')
 d.box(id+'title',x+15,190,w-30,40,title,color,18,False)
# Frontend parameters.
d.box('predict',65,255,300,115,'Branch prediction\nGShare · 8-bit global history\nPHT: 256 × 2-bit counters\nBTB: 16 entries, fully associative','#dbeafe',16)
d.box('icache',65,405,300,100,'Instruction cache\n8 KiB · 4 ways · 64 sets\n32-byte cache lines','#dbeafe',18)
d.box('align',65,535,300,65,'Instruction alignment\n16 / 32-bit instructions','#dbeafe',17)
d.box('queue',65,630,300,80,'Fetch buffer\n6-entry FIFO','#dbeafe',18)
for y1,y2 in [(370,405),(505,535),(600,630)]:d.edge([(215,y1),(215,y2)])
# Architectural state and execution resources.
d.box('decode',455,255,520,55,'Decode / operand selection / hazard control','#dcfce7',18)
d.box('gpr',455,340,245,75,'Integer register file\n32 × 64-bit architectural','#e0e7ff',16)
d.box('fpr',730,340,245,75,'FP register file\n32 × 64-bit architectural','#e0e7ff',16)
d.box('alu',455,445,245,65,'Integer ALU\nArithmetic / logic','#dcfce7',17)
d.box('branch',730,445,245,65,'BRU + JAU\nBranch / jump resolution','#dcfce7',17)
d.box('memctl',455,540,520,55,'MEM control · blocking request / completion','#fef3c7',17)
for id,x,w,t in [('mul',455,155,'MUL'),('div',635,155,'DIV'),('fpu',815,160,'FPU · FP32/64')]:
 d.box(id,x,635,w,65,t,'#dcfce7',17)
 d.edge([(x+w/2,595),(x+w/2,635)])
d.box('fuNote',465,708,500,25,'Multi-cycle units are requested by MEM','#ffffff',14,False)
# Translation, privilege and data cache; capacities match the single profile.
d.box('dcache',1065,255,310,100,'Data cache\n8 KiB · 4 ways · 64 sets\n32-byte lines · LR/SC / AMO','#fef3c7',17)
d.box('mmu',1065,385,310,130,'Sv39 MMU\nI-TLB: 16 entries\nD-TLB: 16 entries\nHardware page-table walk','#fef3c7',17)
d.box('csr',1065,545,310,75,'CSR / trap control\nM / S / U · interrupt delegation','#ede9fe',16)
d.box('pmp',1065,650,310,65,'Physical memory protection\n8 active PMP entries','#ede9fe',16)
# Only major cross-group relationships; not detailed timing/dataflow.
d.edge([(365,670),(410,670),(410,282),(455,282)])
d.edge([(975,567),(1020,567),(1020,305),(1065,305)])
d.box('debug',40,775,960,60,'FASE Controller','#ede9fe',18)
d.box('output',1040,775,360,60,'To L2 / coherence home','#e2e8f0',18)
d.box('note',40,846,1360,22,'Module / parameter overview · single + gshare + linux configuration · Interconnect and cache hierarchy shown separately','#f8fafc',13,False)
d.save()
