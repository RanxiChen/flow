# V1-MEM 外部接口执行报告

起点：`ae3d7ac`，分支 `feat/pcie-fase-20260920`。本地编辑、提交/push；Alan 按 SHA 验证。现有未跟踪文件保留；冻结文件及 `interface/L1DCoreIO.scala` 不改；L1D/L2 内部 TODO 不实现。

## 0. 命名与旧实现清理

| 类别 | 提交 | 动作 |
| --- | --- | --- |
| 后端、观测、合同及环境 | `9fa1263` | V1Scoreboard→Scoreboard、V1Writeback→Writeback、V1BackendObservation→BackendObservation；内部类型、测试、trace 辅助类去 V1；[V1-CYCLE]→[CYCLE]、断言去版本标记 |
| MUL | `3ec9230` | CommittedMulUnit→MulUnit；删除 RiscvMulUnit。其算术 suite 已直接接提交接口，改为 MulUnitSpec，保留全部场景 |
| DIV | `e232725` | CommittedDivUnit→DivUnit；删除 RiscvDivUnit。其算术 suite 已直接接提交接口，改为 DivUnitSpec，保留全部场景 |
| FPU | `b21a139` | CommittedFpUnit→FpUnit；删除 BreezeFpUnit 及旧包装测试；保留 FpUnitSpec 的 tag/commit/kill/乱序/flags 合同 |
| PLRU | `874a62f` | 删除 BreezePLRU，统一 TreePlru；保留缓存行为测试。BreezePLRUSpec 两个场景原本在块注释内（0 个实际测试），删空 suite |

删除引用检查：各删除提交前执行 `git grep -n -w RiscvMulUnit -- design`、`RiscvDivUnit`、`BreezeFpUnit`、`BreezePLRU`，在移除定义/测试后均无输出、退出 1。旧文件自身与其旧测试是唯一直接引用（PLRU 的两处缓存使用先改为 TreePlru）。`FlowFpnewWrapper.sv` 原本就是直连 tag/valid/ready，未发现旧 response 缓存；缓存实际位于 Scala BreezeFpUnit，随类删除。SV 只更新过时注释，没有改 CVFPU 算术配置。

### 删除的旧测试（逐项）

- `fpu/BreezeFpUnitSpec.scala`：execute exact FP32/FP64 arithmetic and report IEEE exception flags。删除旧包装，算术有效行为另加直接 FpUnit 接口测试；不是删掉算术要求。
- `fpu/BreezeFpUnitSpec.scala`：flush an accepted long-latency operation without a stale response。旧全 flush 包装已删除；新 commit/kill/drain 语义由 FpUnitSpec 的 S05_S15 覆盖，已提交 DIV 不得被全 flush 丢掉。

以下旧 suite 依赖 V1LegacyTestAdapter。删除适配层及其全部依赖，未保留旧脉冲 dmem 到新 RTL 的适配。有效行为使用 BackendContractSpec、MduTimingSpec、Mul/DivProtocolSpec、FpUnitSpec 与新增 BackendBehaviorSpec 的原生接口验证；旧完成旁路/停顿拍数不再构成当前合同。

- `design/src/test/scala/backend/V1LegacyTestAdapter.scala`：无独立测试，适配层或辅助函数随依赖删除。
- `design/src/test/scala/backend/BreezeBackendMulSpec.scala`：multiply completion bypass feeds the immediately dependent EXE instruction。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeRedirectPrioritySpec.scala`：WB store fault beats younger branch, memory, sfence and fence side effects。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeRedirectPrioritySpec.scala`：WB mret beats a simultaneous EX branch and satp blocks younger issue。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeRedirectPrioritySpec.scala`：overlapping fetch fault flags select one cause rather than OR causes。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeRedirectPrioritySpec.scala`：keep EPC at the instruction start but report a fault on its second parcel。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeRedirectPrioritySpec.scala`：forward a CSR result to the following SC store operand。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendFpMemorySpec.scala`：load/store FP values with correct width, boxing and byte mask。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendFpMemorySpec.scala`：honor a back-to-back sstatus FS enable before FLD。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendFpMemorySpec.scala`：trap a misaligned FP64 load without issuing a memory request。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendFpTestUtils.scala`：无独立测试，适配层或辅助函数随依赖删除。
- `design/src/test/scala/backend/BreezeBackendFpSpec.scala`：trap FP instructions while mstatus.FS is Off。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendFpSpec.scala`：execute dependent FP64 arithmetic/FMA and commit sticky fflags。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should redirect a predicted-taken branch to fall-through when actually not-taken。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should redirect a predicted-not-taken branch to its taken target。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should train a correctly predicted branch without redirecting。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should not retrain a branch while an older load holds the pipeline。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should drain $name and train its younger branch before taking a timer interrupt。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should repair a stale JALR target exactly once。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should preserve a high Sv39 JALR target from a load。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should keep a correct JALR target without retraining。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendDivSpec.scala`：RV64 divider executes all eight operations, fast paths, and completion bypass。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。

## 验证记录（执行中）

证据根目录：Alan `/home/chen/FUN/flow-runs/20261006-mem-interfaces/`。`00-baseline.log` 为起点实际全量 sbt test；01–05 分别绑定各类命名提交，日志和 exit/sha/start/end 分开保存。此前 V1-BE 的 65/65 是指定 11 suites，不是仓库全量，不能当本次基线。

命令：`source /home/chen/miniforge3/bin/activate flow; cd /home/chen/FUN/flow/design; /home/chen/.local/share/coursier/bin/sbt test`。冻结：`python3 tools/frozen_check.py`，截至当前每次提交前均 OK（9 files）。

## 1. MMU / PMP / PMA 接口

| 接口点 | 来源位置 | 改动与边界 |
| --- | --- | --- |
| TLB req/resp/kill | `mmu/sv39/Sv39MmuBundles.scala:24`；`Sv39Tlb.scala:31,60,72`；MMU spec §4.2 | req.fire 的下一拍为 S1 Valid 响应；hit/miss/pageFault/accessFault 四选一，miss 不是带 PA 的命中；dropS1Next 在 Sv39Tlb 内部由 S1 miss 产生，丢掉同拍已握手的 Y 响应。kill=req 关闭、resp 组合屏蔽，L1D kill 不依赖 resp.valid |
| S1 保持 | `l1d/L1DCache.scala:199` | 首个 S1 周期捕获 TLB 结果，S2 hold 后使用捕获值，避免丢掉单拍响应或读到后续响应。没有 terminal TLB 结果不送虚构 PA 到 S2 |
| PA 高位 | `L1DCache.scala:151,209,236,250` | PTW 56 位、TLB 64 位 PA 高位保留在 physicalAddress，PMA/PMP 用全位宽；只有缓存索引/tag/coherence 用低 32 位。≥2^32 报 access fault，不对低地址发 Get/MMIO |
| PTW 权限与返回 | `Sv39Ptw.scala:48`；MMU spec §6.1；`L1DCache.scala:263,358` | 固定 8 B Load、S-mode PMP；device PTE 读拒绝。PTW 的 Exc 转为 Valid accessFault，不受 CPU kill 作废；响应至少在接受请求后的下一拍，当前无 miss 的流水错误返回在 S2 |
| PMP | `mmu/BreezePmpChecker.scala:11`；`L1DCache.scala:248` | Load/LR→Load，Store/SC/AMO→Store；普通数据的有效特权 MPRV?MPP:privilege。csr 上下文仍按原冻结边界输入 |
| 原子 PMA | `platform/BreezeMcuPlatform.scala:13,122`、`PMAChecker.scala:21,113`；冻结 integration notes §3 | JSON 区域显式 mainMemory/amo/reservability，arithmetic/eventual 仅 main_ram、sram；ROM、device、holes、高 PA 为 false。PMAResult 增 amoOk、rsrvOk；LR 拒绝报 5，SC/AMO 拒绝报 7 |

**未解决的内部衔接（不猜、不实现）：** X/Y 翻译等待队列的 enqueue、TLB miss 后原序重试仍是 `L1DCache` 的内部 TODO；TLB 只返回 miss，walk 完成不主动重发命中响应，L1D 必须重新发 X/Y 请求。当前骨架不具有该闭环，不能据本任务 elaborate 宣称 Sv39 访存可运行。MSHR Replay 的 physicalAddress 需由内部 replayReq 构造补齐。PTW 的 hit/miss 数据获取仍依赖阵列/MSHR TODO，本轮验证的是拒绝路径与接口拍关系。

定向证据：`07-permissions-v2.log` 中 L1DPermissionsSpec 4/4、MemSkeletonElabSpec 10/10、PMA 5/5；该轮 BackendBehaviorSpec 一个旧“后端必须在 L1D 接受前判非对齐”假设失败。按当前 L1D S2 负责对齐检查的边界，改为原地址送到 L1D、后端传播模型回传的 cause=4，实际 L1D 对齐判定仍属内部后续工作。`07-permissions-v3.log` FpUnitSpec 5/5，保留旧 FP32/FP64 精确算术和 IEEE flags 的所有输入/期望，直接走 req/commit/result 并检查反压保持。最终门槛见验证表。

## 5. 事件接口

`L1DBundles.scala` 按 l1d-spec-inputs §12 声明全部 21 字段；`L1DCache.scala:471` 接出当前可观察脉冲/电平。load/store/ptw/lr_access 目前用入口 fire；miss 用 alloc.fire；upgrade 用 alloc.upgrade；Put 区分 hasData；probe 接收用 SNP fire，held 用 pending&&hold；MMIO Read/AW fire 与 busy。hit_under_miss 仅 CPU 完成命中且 MSHR 不同行；MSHR 和同行停顿从现有 status/outcome 导出。

`core/BreezePerformanceCounters.scala:53` 的现有 CSR 事件只提供 1–13：新 load_access|store_access→dcacheAccess，load_miss|store_miss→dcacheMiss，mmio_read|mmio_write→dcacheUncached，其他细分线从集群按 hart 引出，未擅自分配 CSR 编码。访问统计在 req.fire 还是不可撤销 S2 的计数口径，来源只列事件名、未写 kill 计数约定；报告保留当前入口口径供接口审阅。upgrade/probe hold/SC 失败/完整 hit-under-miss 的真实事件取决于内部 TODO，不把恒零的骨架条件声称为已覆盖行为。

基线 CSR 参照测试把 11 当非法选择器，当前事件合同已为 1–13。参照模型更新为全部合法事件与 conflict=0/1/2，并保留非法 14、全宽非法值、随机写、显式写优先、inhibit 和 reset；未更改 RTL 或放宽期望。

## 2. L1I 客户端与功能改名

`cache/BreezeCache.scala` 用 git mv 改为 `l1i/L1ICache.scala`，对应测试改名保留 3 项；`frontend/BreezeFrontend.scala:88,111` 使用 L1ICache，几何由新 L1IParams 直接推导。取消缓存内 4 路硬编码，TreePlru 的 1 路/多路与 metadata 位宽均参数化；config.scala 保持不变。

`l1i/L1IClient.scala` 是任务允许的独立 refill 接口模块：现有 frontend/L1ICache 单拍 demand refill → 保持到 fire 的 coherence.ReadClientIO。两个独立槽分别 id=0 demand、id=1 prefetch，REQ 只发 Read；RSP↓按 id 返回数据/错误，不加入目录 sharer。已发请求在 flush 后仍须消费返回，L1ICache 的 s2_flush_seen 决定丢弃安装，不在 coherence 中增加取消语义。PA 高位拒绝并回 error，避免回绕。REQ 选择寄存后不因新 demand 到来而替换已被反压的 prefetch。

`l1i/FetchTlbClient.scala` 连接现有前端 Decoupled 翻译接口与独立 Sv39 iTLB：一次请求、下一拍响应，miss 后等待 req.ready 并重试，resp 在前端 ready 前保持；Fetch 的 PMP 使用当前特权，不使用 MPRV。它是当前前端与 MMU 的边界模块，不是旧后端测试兼容层。

未补前端的下一行预取触发或预取安装策略：Read id=1 的发送/返回接口已经具备，但既有 L1ICache 仍只有 demand miss 安装入口；该策略缺少本任务要求的具体改造合同，报告保留给后续前端实现，不自行增加隐含预取队列。

Alan `08-l1i-v2.log`：28/28，4 suites，退出 0。其中 MemSkeletonElabSpec 21/21（五种几何下新增 L1ICache/L1IClient，另 FetchTlbClient）、客户端 2/2（两个 ID、REQ 反压保持、乱序返回、error、高 PA）、缓存原回归 3/3、原并行 lookup 2/2。
