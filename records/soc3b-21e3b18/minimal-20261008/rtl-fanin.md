# SOC-3b RTL fan-in mapping

Source SHA: `21e3b187f0eea5d4739929a92b2f71e46761f719`. These source mappings supplement optimized intermediate net names; they do not replace available post-synthesis timing queries.

| Query / endpoint | Direct source dependency | Boundary |
| --- | --- | --- |
| W2 bank/rd/data and physical RF authorization | Writeback.scala:47-64, 100-107; only w2 registers and registered/background result outputs | S2 -> ordinary capture -> W2 is allowed; ordinary input cannot fall through RF |
| late ready / DIV,MUL,FPU ready | Writeback.scala:57-73; lateReg valid, registered W2 and actual background selected | Original L1D late payload/valid only updates lateReg D |
| actual clear | Writeback.scala:89-110 -> BreezeBackend.scala:198 -> Scoreboard.scala:50-55 | grant-derived clear distinct from S2/Mshr set |
| RAW/WAW metadata | BreezeBackend.scala:210-225 and 426-430; pipeline metadata, busy registers and clear above | WB memory qualification does not use response kind or wbDone |
| MEM/WB/W2 EX bypass selection | BreezeBackend.scala:230-244; registered valid, kind, bank, rd and payload | MEM > WB > W2 > saved; no response/commit/done selection |
| held EX capture / final advance | BreezeBackend.scala:431-474 | downHold/kill/fatal final advance/cancel gating is an allowed path, not data bypass qualification |

Generated fan-in source files are archived alongside this mapping. Whole background result validity also traverses DivUnit/MulUnit/FpUnit; their commit/cancel effects update state rather than authorizing speculative writes. RTL and generated-source provenance matches the source manifest.
