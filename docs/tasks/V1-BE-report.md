# V1-BE 执行报告

状态：**发现冻结接口矛盾 B01，完整后端实现与合同验收未完成**。按任务书 §3.3 暂停受影响部分，独立部分继续。未修改任何冻结输入或期望。

## 1. B01：WB 保持无法传到 L1D

依据：

- `backend-timing-contract.md` §1/T12：后台写占口，普通 WB 写让拍，四级保持。
- 同合同 §3：EX/MEM/WB 与 S0/S1/S2 一一对齐；后端与 L1D 之间不加额外 FIFO/结果缓冲。
- `l1d-rtl-spec.md` §1.1：req 是 EX→S0 Decoupled；resp 是 S2→WB Valid、每请求恰一次，没有 ready；s2Hold 是 L1D→后端，没有后端→L1D hold。
- L1D §3：只有 **L1D 的 S2 暂不判定** 时 S1/S2 保持；s1Kill/s2Kill 的语义是作废，不是保持。

合法反例：更老的 Load A 已经提交为 miss；B 是普通 `add x2,...`；C 是年轻、不同字的命中 `ld x3,...`。令 A 的迟到结果恰在 B 的 WB 拍 N 返回（环境可以选择 RSP↓ 延迟）。不依赖任何具体地址或 PC。

| 拍 | 后端当前状态/必须执行动作 | L1D 当前状态/冻结规则动作 |
| --- | --- | --- |
| N−1 | B 在 MEM；C 在 EX，`req.fire(C)` | C 进入 S0 |
| N | B 在 WB；C 在 MEM；A 的 late 占整数写口，B 写让拍，四级保持 | C 在 S1；S2 空，不存在暂不判定；无任何接口信号可保持 C，周期末进 S2 |
| N+1 | B 仍在 WB、C 仍在 MEM；B 此拍才写回/提交；C 周期末才进入 WB | C 在 S2 命中，`resp.valid(Done)` 本拍唯一一次，且本拍不能反压 |
| N+2 | C 第一次在 WB，须接收自己的 S2 判定/写回 | C 的唯一 resp 已在上一拍发完；没有对应 S2 请求/数据可消费 |

因此仅把后端四级保持，无法保持 EX/MEM/WB↔S0/S1/S2 对齐。关 req.valid 只关闭新 S0，不能停住已经接收的 S1；用 kill 会撤销 C 且需新增重发规则；临时保存 C 的响应则触及禁止的结果缓冲；让 A 等 B 则改变 T12 后台优先合同。这里未擅自选任何修复。

更直接的边界是：命中 Load 自己在 WB/S2，与后台 GPR 写同拍；L1D 当拍唯一 Done，而 WB 被要求保持到下一拍，也没有 resp.ready。此边界同样必须在裁定中覆盖。

需要 Claude/用户裁定的是**如何在保持冻结拍数、吞吐和单写口规则的同时，使后端 WB 保持与 L1D S1/S2 协同**。这不是实现细节，不能标成 [自定] 掩盖。

## 2. 已完成的独立产物

- `docs/backend-v1-rtl-spec.md`：带逐规则来源的 v1 规范；B01处显式标明不能实施。
- `interface/L1DCoreIO.scala`：逐字段声明冻结接口，无隐藏 hold/ready/ID。
- `backend/V1Scoreboard.scala`：双 bank、f0有效、交叉目的、四来源、级间生产者、真实 grant 清位、CSR 原始 busy 等空、来源事件与仿真断言。
- `backend/V1Writeback.scala`：无缓存四来源仲裁、每 bank 单写、普通写让拍、flags完成事件、late.error 清位与 sticky hartFatal。
- `fpu/CommittedFpUnit.scala`：32项 metadata-only 在途表、5位 tag、直接 CVFPU 输入/输出、最老 commit、未提交 kill、作废返回丢弃、已提交项保留。
- `BreezeFp.scala` / `FlowFpnewWrapper.sv`：参数化 tag 接线；旧阻塞包装仍供现有后端使用，v1 独立单元绕过其 response 缓存。
- 新增单元自检：V1ScoreboardSpec、V1WritebackSpec、V1MduTimingSpec、CommittedFpUnitSpec。

**BreezeBackend 尚未切换到上述模块**；旧后端/核心接线、trace/HPM扩展、FENCE.I/SFENCE WB 控制、loadUseBypass参数和行为L1D后端测试待B01裁定后继续。没有把独立组件冒称完整后端或集群。

## 3. [新决定]

| ID | 决定 | 理由/影响 | 裁定 |
| --- | --- | --- | --- |
| ND01 | FP kill 后关闭新 FP 分配，直到 CVFPU busy=0；已提交返回照常写、作废返回丢弃，不 flush CVFPU | 表 valid 清掉后，旧 tag 仍可能返回；立即复用会误写新项。仅增加 killDrain 控制位、不添 table 字段/结果缓存；trap后新FP可能延后 | 待 Claude |
| ND02 | CSR 除两组原始 busy/未提交流水外，也等已提交 FP→x0 项返回 | x0不能busy，但这些FP项仍可能累积fflags；用在途表 metadata 的组合归约，不新增存储；CSR等到最后flags完成的下一拍 | 待 Claude |

## 4. [自定] 摘要

- L1D op/resp 枚举按表顺序编码，cause/tval 64位；Cache-side IO，backend取Flipped。
- 两 bank 都用32位 bitmask，整数 bit0不可置位；来源2位，L1D/DIV/MUL/FPU编码0/1/2/3。
- 全局一个后台grant，各bank独立普通写；普通写只有同bank冲突才保持。
- FP表32项、tag5位、循环allocate/commitCursor；按顺序扫描最老未提交项，没有数据/flags字段。
- 仿真组件测试名用 `*_component` 标明边界；不列为 backend 合同通过。
- fatal 状态复位清零、错误当拍可见；错误清目的busy但不写数据，普通退休停止，已提交后台完成仍可消费。

## 5. 提交与验证

起始本地分支 `feat/pcie-fase-20260920`，HEAD `e479a1ba295a746bca0547ee5141dd2e74936bdb`；已有未跟踪文件保留。Alan 原 `/home/chen/FUN/flow` 是干净 detached HEAD `51b62b9286e60ffec74902e34f9f64047930df79`；未占用其工作区或改其HEAD，验证使用独立checkout。

首个独立实现提交 `95b4f1615b923f3f77c261c834280e8a5240fa3a`（`V1-BE/1`）已push。Alan在独立clone从GitHub fetch并checkout该SHA；CVFPU SHA `1b220f3bc89df99e246b72e3574a3a533cf87653` 未改。

首轮命令在 `/home/chen/FUN/flow-runs/20261006-v1-be-95b4f16/repo/design` 执行（先 `source /home/chen/miniforge3/bin/activate flow`）：

```bash
/home/chen/.local/share/coursier/bin/sbt -batch \
  'set Test / parallelExecution := false' \
  'testOnly flow.backend.V1ScoreboardSpec flow.backend.V1WritebackSpec flow.backend.V1MduTimingSpec flow.fpu.CommittedFpUnitSpec flow.multiplier.CommittedMulProtocolSpec flow.divider.CommittedDivProtocolSpec flow.backend.MduBoundarySpec flow.fpu.BreezeFpUnitSpec'
```

日志 `/home/chen/FUN/flow-runs/20261006-v1-be-95b4f16/units.log`，环境 `environment.log`，执行脚本 `run.sh`、命令 `command.txt`、退出码 `exit-code.txt` 同目录。实际首轮：16测试通过、0失败，6 suite完成、2个FP suite aborted，整体退出码1。原因是独立checkout未初始化CVFPU嵌套 `src/common_cells`，不是FP通过；补齐该固定依赖后再验证。工具：OpenJDK11.0.32.1、实际sbt1.9.7（launcher脚本1.11.2）、Verilator5.028。未运行完整 `sbt test`、ACT4、整核/集群、综合/时序或形式化。

本地和Alan `python3 tools/frozen_check.py` 均输出 `frozen check: OK (8 files)`；冻结检查仅证明文件完整性。补充ND02后的最终提交与组件复验结果待填。

## 6. 合同逐项状态

下面“未运行”均指**真实后端合同测试**。组件测试不替代 ID离开/EX/WB提交/物理RF写的端到端观测。

| ID | 状态 | 组件证据或阻塞范围 |
| --- | --- | --- |
| T01 | 未运行 | ALU/真实后端旁路未迁移 |
| T02 | 未运行 | B01，默认load-use |
| T02b | 未运行 | B01，可选S2→EX参数 |
| T03 | 未运行 | B01，命中Load与独立ALU |
| T04 | 未运行 | 有四级MUL组件精确拍数测试，非ID依赖测试 |
| T05 | 未运行 | 有DIV快路径组件精确拍数测试 |
| T06 | 未运行 | 有实际迭代拍数组件测试 |
| T07 | 未运行 | 有DIV释放/再接收组件测试，非EX保持测试 |
| T08 | 未运行 | B01，L1D S0同字冲突 |
| T09 | 未运行 | B01，L1D S0不同字 |
| T10 | 未运行 | B01，Mshr在真实WB提交 |
| T11 | 未运行 | B01；静态推导同合同R+7，未仿真 |
| T12 | 未运行 | B01；有仲裁组件，未验证四级保持 |
| T13 | 未运行 | 有四来源同拍、四拍顺序组件测试 |
| T14 | 未运行 | 有CVFPU直接输入组件测试，非EX观测 |
| T15 | 未运行 | 有CVFPU直接输出组件测试，非物理RF写 |
| T16 | 未运行 | 未实现真实FP ID依赖测试 |
| T17 | 未运行 | 有CSR记分板等待组件测试，非CSRFile读fflags |
| T18 | 未运行 | WB FENCE.I尚未接入 |
| T19 | 未运行 | WB FENCE.I/drained尚未接入 |
| T20 | 未运行 | WB SFENCE/MMU请求关闭尚未接入 |
| P01 | 未运行 | B01，16 Load吞吐 |
| P02 | 未运行 | 有8 MUL组件吞吐测试，非真实GPR写 |
| P03 | 未运行 | DIV与20 ADD退休重叠尚未接入 |
| P04 | 未运行 | B01，hit-under-miss |
| P05 | 未运行 | B01；静态推导同合同R+10，未仿真 |
| P06 | 未运行 | 三后台来源与20 ALU真实提交尚未接入 |
| P07 | 未运行 | 有真实CVFPU跨单元乱序返回/flags组件测试 |
| P08 | 未运行 | 有8 FMA组件输入吞吐测试，非真实EX发射 |
| P09 | 未运行 | 中断不等后台尚未接入 |
| P10 | 未运行 | WB异常与未提交MUL年龄取消尚未接入 |

完整合同测试并不存在；没有创建 placeholder、忽略/跳过测试或放宽期望。本轮仅报告B01这一项冻结合同冲突，独立单元的实际结果另列。
