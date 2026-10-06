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
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should drain multiply and train its younger branch before taking a timer interrupt。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should drain divide and train its younger branch before taking a timer interrupt。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should drain floating add and train its younger branch before taking a timer interrupt。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should repair a stale JALR target exactly once。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should preserve a high Sv39 JALR target from a load。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should keep a correct JALR target without retraining。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendDivSpec.scala`：RV64 divider executes all eight operations, fast paths, and completion bypass。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。

## 收尾状态（2026-10-06，Claude 按用户指示代为提交）

- 第 0、1、2、5、6 项已完成；1、2 及命名类提交的 Alan 验证见下方各节记录。
- 第 3–4 项（`BreezeCluster`、`BreezeClusterWishbone`、`Axi4WishboneBridge`、`Axi4LiteArbiter`、`DmaWishboneClient`，删除旧 D-cache/L2/集群及其测试）是 codex 暂存的最终改动，由 Claude 原样提交。**本提交在 Alan 上的全量 `sbt test` 与 `MemSkeletonElabSpec` 未运行**，结果待下一轮 Alan 验证。
- 后续：L1D/L2 内部 `TODO` 由 Claude 实现；codex 只按指定 SHA 在 Alan 运行测试并原样贴输出。

## 验证记录

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

## 3–4. 集群、AXI / Wishbone 与 DMA

`top/BreezeCluster.scala:20` 用 BreezeMemGeometry 实例化每 hart 的 BreezeBackend、BreezeFrontend/FetchBuffer、L1DCache、L1IClient、Sv39Mmu、FetchTlbClient；共享 L2Home。Backend 的冻结 L1DCoreIO 直接接 L1D；dTLB/PTW 直接接 MMU，satp/priv/MPRV/MPP/SUM/MXR 和 sfence 操作数来自 CSR 输出。FetchTlbClient 在 translationBlocked 时禁止接受新的翻译，已接受请求仍排空；frontend flush 杀掉未完成的取指翻译。`L2Home.mem` 是原生 AXI4，per-hart MMIO 经 Axi4LiteArbiter，DMA 是独立 ReadClientIO，无目录身份。

实际集群 elaborate 暴露 S2 反馈组合环：Backend 用 resp.valid/Exc 生成 WB kill，L1D 却用 s2Kill 屏蔽该 resp.valid。`L1DCache.scala:327` 改为 S2 判定输出与 kill 无组合依赖；kill 仍清尚未判定的寄存状态，门控 MSHR 分配和 PS 写入。异常判定不能屏蔽产生自身 kill 的响应，WB 忽略与当前访存不匹配的判定。新 L1DPermissionsSpec 的 killOnFault 闭环证明拒绝路径能返回一次异常且不发出访存请求；没有修改冻结接口或缓存内部 TODO。

`bus/Axi4WishboneBridge.scala:11` 支持当前内存引擎使用的 64-bit SIZE=3、INCR，保留 AR/AW ID 与 LEN/LAST；AW/AR/W 分别有 1 槽，接受 W 先于 AW，完整事务串行转换为经典 Wishbone beat。R/B 在 ready 前保持，WB err 映射 SLVERR，burst write 汇总各 beat 的错误。`Axi4LiteWishboneBridge` 用 ID=0/LEN=0 转换 MMIO。AXI 全功能（窄传输、非 INCR、并行 transaction 重排）未实现，输入协议断言明确约束。64-bit MMIO 的尺寸通过 WSTRB；既有 AXI-Lite 读端口没有窄读尺寸字段，本任务不擅自增加，设备侧是否允许全 8 B 读取须另行确认。

`bus/Axi4LiteArbiter.scala:9` 一次一个 hart 事务，轮转选择；owner 跨独立 AW/W 握手保持直到 R/B 被原 hart 消费。允许 W 先到、AW 延迟及返回反压。`top/BreezeClusterWishbone` 包装成 LiteX 所需的两个 64-bit、32-bit byte address 的 Wishbone 主口（adr 为 29-bit word address）。

`bus/DmaWishboneClient.scala:11` 一次一个 Wishbone beat：Read→整行 Read、id=0，再按 adr 低 word offset 选 64-bit；write→MaskWrite，8-bit SEL 与 64-bit DATA 分别左移到 32 B 行的 byte/bit 位置。WriteAck 才回 ACK，error 回 ERR，CYC 持续时可接受下一 beat。DMA 不添加 sharer；ReqOp.Read/MaskWrite 在 L2 的 probe/merge/slot 执行仍是内部 TODO，本测试不声称 coherent DMA 数据功能已实现。Wishbone 请求一旦送到 coherence 不可取消；主机提前撤销 CYC 的返回被排空、不产生 ACK，复位/中止协议仍需调用者明确。

新顶层各五种几何 elaborate 后，删除 `cache/BreezeDCache.scala`、`cache/BreezeL2Home.scala`、`cache/Coherence.scala` 与 `top/BreezeMulticoreClusterWishbone.scala`；旧 cache 全部依赖测试一起删除，I-cache 并行 lookup 场景保留。移除后 `git grep -n -w -e BreezeDCache -e BreezeL2Home -e BreezeMulticoreClusterWishbone -e BreezeCoherenceReqIO -e BreezeCoherenceOpcode -- design/src` 无输出，退出 1。生成器 git mv 为 `top/GenerateBreezeCluster.scala`，输出 `build/rtl/mem-cluster/...`，不覆盖历史 FPGA 输出和 timing/PPA 报告。

### 外壳仍需确定的合同

- `litex_wrapper/flow/core.py:245,325,383` 与 `fpga/kcu105/target.py:343` 仍引用旧生成器、顶层名、dcacheTrace/FASE/flight 引脚、旧目录/marker 格式。新的生成器只接受 dma/tandem；板级 FASE/PCIe/debug 需要新后端控制与观测合同，不能靠空引脚兼容。这些板级消费者本轮未迁移、未构建；不能拿历史 bitstream/timing 作为新集群证据。
- 新 Backend 当前 require(!useFASE)，旧 FASE Core/集群 integration 测试本来无法 elaborate。删除依赖旧集群的两项 integration 与旧 FASE Core 驱动测试；独立 FaseDecoder/FlightRecorder/JTAG mailbox 等组件测试保留。FASE 接回新 Backend 尚未解决。
- Prefetch id=1 客户端已有测试，但集群没有前端触发/安装合同，故 valid=0；不把该接口称为已实现预取功能。
- 现有 sfence 的 idle/blocked、取指 kill 已按接口连接；真实 MMU walk 经 L1D/L2 前进仍依赖内部 miss/replay TODO。该集群的验收为 elaborate，整机仿真、PPA/FPGA/runtime 均未运行。

### 删除的旧缓存/集群测试（逐项）

下列测试随已删除的旧 RTL 与脉冲 coherence 协议删除。其目录状态、回填、驱逐、原子、probe、coherent DMA 等有效功能需求继续保留在冻结 spec；新骨架内部实现未完成，不能用接口测试宣称替代了这些功能验证。报告逐项留作后续内部验证迁移清单。
- `design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala`："DCache elaboration should reject non-frozen geometries" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala`："DCache should fill four ways with exactly one GetS each and no Put traffic" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala`："DCache should evict the tree-PLRU victim, proven by PutM identity" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala`："DCache should not evict lines across different sets" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala`："DCache should merge partial-mask stores at all lane offsets" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala`："DCache should report a refill error after the victim was already released" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala`："DCache should deny out-of-region accesses without any traffic" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala`："DCache should bypass MMIO with beat-aligned address and byte mask" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala`："DCache should propagate MMIO errors to the CPU response" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala`："DCache flush should release every valid line (PutM dirty, PutS clean) and invalidate" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala`："DCache flush release error should raise the sticky fatal error" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala`："DCache HPM should count access, miss and uncached exactly once per request" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："GetS grants E; a store on the E line silently upgrades to M without GetM" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："a clean E line answers ProbeToS without data and degrades to S" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："a dirty M line answers ProbeInv with data and is invalidated" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："an S line store issues GetM and completes with a dataless upgrade grant" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："srcHart tags requests and probe responses with the configured hart id" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`：refill installation preserves AMO data with probe at grant。理由：依赖已删除旧缓存及旧协议，内部 refill/probe 验证待实现后迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`：refill installation preserves AMO data with probe at install。理由：依赖已删除旧缓存及旧协议，内部 refill/probe 验证待实现后迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："upgrade race: a probe kills the S copy while GetM waits; the data grant repairs it" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："Put cancel: a probe that takes the parked victim cancels the PutM" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："CPU pulse arriving during an unsolicited probe is queued and served after it" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："LR/SC succeeds locally on an E line without a GetM" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："LR/SC succeeds through a GetM upgrade on an S line" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："SC without a reservation fails fast with no traffic and no write" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："SC with a mismatching size fails even at the reserved address" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："a probe on the reserved line clears the reservation (SC fails without GetM)" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："SC reservation killed while the GetM waits: fail, no write, clean E install" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："resKill clears the reservation" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："AMO hit on E: old value returned, new value written, no coherence traffic" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："AMO.W modifies only the addressed 32-bit half and returns the aligned old word" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："AMO miss allocates with GetM and returns the pre-modification word" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："AMO on an S line upgrades with GetM before the RMW" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："atomics on a device region fail with an access error and no traffic" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："eviction of the reserved line clears the reservation" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`："conditionally update a PTE without modifying a concurrently replaced mapping" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSpec.scala`："shared array payloads preserve every way across dirty hits and replacement" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSpec.scala`："a recalled hit stays stable under grant backpressure before a clean recall" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSpec.scala`："a GetS miss refills from memory with four beats and grants E (MESI)" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSpec.scala`："a dirty PutM line stays in the L2 without memory traffic" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSpec.scala`："a dirty NONE victim is written back with ascending beats" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSpec.scala`："an evicted clean E victim is recalled without data and without writeback" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSpec.scala`："I$ refill allocates in the L2 but never joins the D$ directory" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSpec.scala`："I$ refill of a UNIQUE line recalls the D$ owner's data via ProbeToS" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSpec.scala`："eviction of a SHARED victim invalidates the L1 sharer" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSpec.scala`："a refill error does not install and does not lose the evicted victim" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSpec.scala`："a victim writeback error preserves the dirty victim in the L2" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSpec.scala`："Idle arbitration is round-robin between the harts" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSpec.scala`："a pending I$ refill is not starved by back-to-back coherence requests" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSpec.scala`："T4 dual: sharing, upgrades, dirty transfer and serialization under MESI" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSmallSpec.scala`："S1 four simultaneous GetS requests serialize into one E owner and three S joiners" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSmallSpec.scala`："S2 a four-way SHARED line upgrade must wait for all three probe Acks before GrantM" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSmallSpec.scala`："S3 a dirty owner on hart 3 must transfer the latest full line to hart 1 and then share with hart 2" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSmallSpec.scala`："S4 inclusive eviction of a four-sharer line must invalidate every L1 and refill on re-access" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSmallSpec.scala`："S5 eviction of a hart-3 dirty UNIQUE victim must write back the recalled line" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSmallSpec.scala`："S5-error a failed writeback must keep the dirty UNIQUE victim readable instead of losing it" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSmallSpec.scala`："S6 four I$ refill pulses captured while busy must answer once each and never join the D$ sharers" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeL2HomeSmallSpec.scala`："S7 Idle arbitration rotates over four continuously requesting harts without starvation" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeCoherentDmaSpec.scala`："single hart DMA reads recall silent E to M stores without allocating a DMA owner" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeCoherentDmaSpec.scala`："four sharers all acknowledge invalidation before a DMA partial write completes" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeCoherentDmaSpec.scala`："DMA write preserves untouched dirty bytes and CPU subsequently sees the merged line" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeCoherentDmaSpec.scala`："DMA misses use the existing refill and dirty eviction including the top of 2 GiB DDR" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeCoherentDmaSpec.scala`："DMA rejects devices and recovers from a refill error without granting stale data" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/cache/BreezeCoherentDmaSpec.scala`："consecutive Wishbone beats with CYC held high complete exactly once" in {。理由：依赖已删除旧缓存/集群及旧协议；新接口能 elaborate，相关内部功能验证待迁移。
- `design/src/test/scala/fase/FaseIntegrationSpec.scala`：command decode controls a Linux core, diagnoses stalls and launches coherent DDR code。理由：依赖已删除旧集群与禁止的 FASE 后端入口；保留独立 decoder/mailbox 组件测试，整机控制合同待确认。
- `design/src/test/scala/fase/FaseIntegrationSpec.scala`：JTAG mailbox controls a Linux core, diagnoses stalls and launches coherent DDR code。理由：依赖已删除旧集群与禁止的 FASE 后端入口；保留独立 decoder/mailbox 组件测试，整机控制合同待确认。
- `design/src/test/scala/cache/BreezeParallelLookupSpec.scala`："D-cache overlaps virtual lookup, reuses it for load/store/AMO, and replays after PTW or probe" in {。理由：仅该 D-cache harness 依赖旧 RTL；I-cache 测试保留。

### Core 测试台迁移

直接 FASE 输入与旧 dmem CPU 请求测试随旧执行入口删除；新 Backend 明确禁止 useFASE，未保留测试适配层。ALU/CSR/MDU/FP/访存 hazard 的有效行为由 native BackendContractSpec、BackendBehaviorSpec、MduTimingSpec 和组件 tests 覆盖；旧 pipeline 固定拍数不作为新合同。
- `design/src/test/scala/core/breezecoreSpec.scala`：BreezeCore should retire a single addi instruction from FASE input。理由：依赖已禁止的 FASE Core 入口或旧 dmem CPU 协议；有效行为改走 native 后端合同测试。
- `design/src/test/scala/core/breezecoreSpec.scala`：BreezeCore should pipeline consecutive addi instructions from FASE input。理由：依赖已禁止的 FASE Core 入口或旧 dmem CPU 协议；有效行为改走 native 后端合同测试。
- `design/src/test/scala/core/breezecoreSpec.scala`：BreezeCore should execute RV64I R-type ALU instructions through the pipeline。理由：依赖已禁止的 FASE Core 入口或旧 dmem CPU 协议；有效行为改走 native 后端合同测试。
- `design/src/test/scala/core/breezecoreSpec.scala`：BreezeCore should retire dependent add/sub chains without pipeline stalls after first writeback。理由：依赖已禁止的 FASE Core 入口或旧 dmem CPU 协议；有效行为改走 native 后端合同测试。
- `design/src/test/scala/core/breezecoreSpec.scala`：BreezeCore should stall on CSR rd dependencies until CSR writeback is available。理由：依赖已禁止的 FASE Core 入口或旧 dmem CPU 协议；有效行为改走 native 后端合同测试。
- `design/src/test/scala/core/breezecoreSpec.scala`：BreezeCore should stall adjacent CSR operations targeting the same CSR until wb commit。理由：依赖已禁止的 FASE Core 入口或旧 dmem CPU 协议；有效行为改走 native 后端合同测试。
- `design/src/test/scala/core/breezecoreSpec.scala`：BreezeCore should write CSR via CSRRW (RW command) with rd=0。理由：依赖已禁止的 FASE Core 入口或旧 dmem CPU 协议；有效行为改走 native 后端合同测试。
- `design/src/test/scala/core/breezecoreSpec.scala`：BreezeCore should execute supported RV64I load instructions through dmem。理由：依赖已禁止的 FASE Core 入口或旧 dmem CPU 协议；有效行为改走 native 后端合同测试。
- `design/src/test/scala/core/breezecoreSpec.scala`：BreezeCore should retire a delayed load exactly once。理由：依赖已禁止的 FASE Core 入口或旧 dmem CPU 协议；有效行为改走 native 后端合同测试。
- `design/src/test/scala/core/breezecoreSpec.scala`：BreezeCore should execute an adjacent load-to-store dependency exactly once。理由：依赖已禁止的 FASE Core 入口或旧 dmem CPU 协议；有效行为改走 native 后端合同测试。
- `design/src/test/scala/core/breezecoreSpec.scala`：BreezeCore should resolve an adjacent load-to-branch with the loaded value。理由：依赖已禁止的 FASE Core 入口或旧 dmem CPU 协议；有效行为改走 native 后端合同测试。
- `design/src/test/scala/core/breezecoreSpec.scala`：BreezeCore should execute supported RV64I store instructions through dmem。理由：依赖已禁止的 FASE Core 入口或旧 dmem CPU 协议；有效行为改走 native 后端合同测试。
- `design/src/test/scala/core/breezecoreSpec.scala`：BreezeCore should pulse estop for one cycle when a single ESTOP retires from FASE input。理由：依赖已禁止的 FASE Core 入口或旧 dmem CPU 协议；有效行为改走 native 后端合同测试。

保留 CoreNoFASE 前端/trap/CSR 程序，直接初始化新增 L1DCoreIO 与 mmuIdle 输入。原三项失败把 ecall/illegal trap 算正常 commit；现改为故障 PC 不提交，仍要求准确 trap、mcause/mepc、handler、mret 和返回路径。ESTOP 测试从旧单拍脉冲更新为提交一次、保持 halted、不提交年轻指令。

`sim/BreezeCoreSimSupport.scala` 的 runner 直接驱动 L1DCoreIO 的 S1/S2、s2Hold/kill/Valid Done/Exc，不使用 RTL 或旧脉冲 CPU 适配层。沿用原平面内存的 load/store 字节/符号/FLW 格式与可配置等待；CPU Sv39 在仿真模型中直接读页表，LR/SC 用独立 reservation 模型。旧 dmem 仅继续服务 standalone BreezeCore 的 instruction PTW。模型没有 cache/coherence/PMP/PMA，不构成真实 L1D/L2 的功能证据；AMO 程序明确拒绝，应走专用协议测试。Trace 改为在时钟沿前采样实际被消费的 WB commit/late，ESTOP 在采样提交后走边沿再退出，避免旧沿后采样漏掉末条指令/读到未更新响应。

旧 Backend 场景映射：MUL/DIV 依赖→T04–T07 与 MduTimingSpec；held branch / older fault→S09_BTB、S13_WB；FP 依赖/flags→T14–T17/P07/P08 和 FpUnitSpec 精确算术；FS-off、紧邻 sstatus FS enable、FP 请求宽度、CSR→SC operand、JALR 高位、取指 fault parcel→BackendBehaviorSpec。旧“timer 必须等 committed DIV/FP 排空”与新 P09（不等已提交后台 DIV）冲突，删除旧排空时序要求；没有擅自改回后端。旧 misaligned FP“不得发 L1D”改为原地址到 L1D、后端传播 cause=4，真实 L1D 对齐检查仍为内部缺项。旧 WB mret 同拍优先级组合未增加专门 native 测试，保留 Core mret 返回回归；该组合覆盖缺口明确留给后端后续验证。

## 6. config.scala 旧几何迁移清单（只列，不改配置）

`config.scala` 与起点 ae3d7ac 完全一致。未来在 BreezeClusterConfig 加 `mem: BreezeMemGeometry` 后统一传给模块；本轮新集群直接接受该几何对象。参数缺口不通过新增旧类默认值掩盖。

| 旧字段 / 来源 | 目标与处理 |
| --- | --- |
| DefaultICacheConfig: ICACHE_LINE_BYTES/SET_NUM/WAY_NUM | mem.lineBytes/l1Sets/l1iWays；旧 64 sets 默认与新 default 128 sets 不同，需要明确选择，不可当同一配置的 PPA/运行证据 |
| FETCH_WIDTH、ICACHE_BYTES_OFFSET_WIDTH | 取指 parcel/beat 属性，保留在前端参数；不冒充缓存几何。当前客户端只有 32 B line，前端指令 beat 仍 32-bit |
| ICACHE_LINE_WIDTH/LINE_OFFSET_WIDTH/INDEX_WIDTH/TAG_WIDTH/PLRU_WIDTH/META_WIDTH | 从 mem/CoherenceParams/L1IParams 推导；tag 应按 PA 而非 VLEN 推导，PLRU 支持 1 路及可变路数，移除旧固定 4 路 assert |
| DefaultICacheConfig.VLEN/PLEN；DefaultDCacheConfig.VLEN/PLEN | VLEN 仍 CPU/取指地址宽度（64）；实现 PA 为 mem.paddrBits=32，但翻译/PMP/PMA 输入须保留 64/56 位高 PA，不能提前截断 |
| DefaultDCacheConfig: capacityBytes/lineBytes/ways/sets | mem.l1Sets * lineBytes * l1dWays / mem.lineBytes / mem.l1dWays / mem.l1Sets；独立 capacityBytes 构造字段退役 |
| DCache: lineWidth/lineOffsetWidth/setIndexWidth/wayIndexWidth/tagWidth/plruWidth/metaWidth | 从 L1DParams(mem) 推导；meta 布局按当前 L1TagEntry，不沿用旧 packed 状态编码 |
| BreezeCoreConfig.dcacheCapacityBytes/dcacheLineBytes/dcacheWays/dcacheCfg | 去掉重复几何输入；coreCfg 保留执行/特权/预测器参数，clusterCfg.mem 传入 L1D 与前端，单独 Core 仿真也需显式几何 |
| BreezeFrontendConfig.cacheCfg；BreezeClusterConfig.l1i/l1d | 不再隐含创建旧 Default*CacheConfig，改为模块直接接 mem / L1IParams / L1DParams；当前 standalone 前端显式保留 64-set 默认，集群传入 mem |
| L2CacheGeometry.capacityBytes/lineBytes/ways/banks/sets | nCores*l2BytesPerCore / mem.lineBytes / mem.l2Ways / 现固定单 bank / CoherenceParams.l2Sets；旧 numHarts*2*L1D 容量公式移除，不与新默认每核 64 KiB 混同 |
| L2 lineWidth/lineOffsetWidth/setIndexWidth/wayIndexWidth | 从 CoherenceParams(mem) 推导；AXI 数据仍 64-bit，整行链路 256-bit，不能互换 lineWidth 与 bus width |
| BreezeClusterConfig.numHarts/hartIdWidth/sharerWidth/txnIdWidth | mem.nCores / CoherenceParams.coreBits / nCores；新请求 id 为 1-bit（L1I demand/prefetch），L2 AXI id 为 slotBits；旧统一 txnIdWidth=2 不可直接搬运 |
| BreezeClusterConfig profileName、corePreset、privilegeProfile、loadUseBypass | 保留非几何参数；profile 支持集合 {1,2,4} 与 mem 支持 1..8、power-of-two L2 sets 的约束须由未来配置归并明确 |
| 旧要求 L1I/L1D capacity/ways 相等 | 删除该旧耦合，mem 已有独立 l1iWays/l1dWays；共同 lineBytes/l1Sets 为本轮既定几何 |
| 新 mem.l1dMshrs/l2Slots/l2BytesPerCore | 旧 config 无对应可迁移输入；直接按新几何声明，不从旧缓存内部常数或 txnIdWidth 推测 |

### 起点全部旧几何引用位置

下面逐行保存 ae3d7ac 的源引用，包含定义/派生/断言、构造参数传递、RTL/测试台与 preset 验证。检索旧类型、独有字段、派生字段与 cluster 几何句柄后按变量所属类型筛选；排除同名 Sv39 TLB geometry、新 CoherenceParams 及原生 L1DCoreIO 端口。被删除的旧文件仍列出，方便迁移需求追踪；不修改历史报告。完整宽搜为 339 行，本表保留旧几何相关文件的全部命中。

```text
design/src/main/scala/cache/BreezeCache.scala:7:import flow.config.DefaultICacheConfig
design/src/main/scala/cache/BreezeCache.scala:88:    val s1_meta = Output(UInt(DefaultICacheConfig().META_WIDTH.W))
design/src/main/scala/cache/BreezeCache.scala:89:    val s1_tag_hit = Output(UInt(DefaultICacheConfig().ICACHE_WAY_NUM.W))
design/src/main/scala/cache/BreezeCache.scala:97:    val data_array_we = Output(UInt(DefaultICacheConfig().ICACHE_WAY_NUM.W))
design/src/main/scala/cache/BreezeCache.scala:98:    val tag_array_we = Output(UInt(DefaultICacheConfig().ICACHE_WAY_NUM.W))
design/src/main/scala/cache/BreezeCache.scala:108:class BreezeCache(val cacheConfig: DefaultICacheConfig, val enabledebug: Boolean = false,val inspectsram:Boolean=false,
design/src/main/scala/cache/BreezeCache.scala:113:        val drsp = Decoupled(new BreezeCacheRespIO(cacheConfig.VLEN,cacheConfig.FETCH_WIDTH))
design/src/main/scala/cache/BreezeCache.scala:116:        val next_level_rsp = new L1CacheMissRespIO(cacheConfig.ICACHE_LINE_WIDTH)
design/src/main/scala/cache/BreezeCache.scala:121:    assert(cacheConfig.ICACHE_WAY_NUM == 4, "当前只支持4路组相连的cache")
design/src/main/scala/cache/BreezeCache.scala:122:    require(!parallelLookup || cacheConfig.ICACHE_SET_NUM * cacheConfig.ICACHE_LINE_BYTES <= 4096,
design/src/main/scala/cache/BreezeCache.scala:127:            region.origin % cacheConfig.ICACHE_LINE_BYTES == 0 &&
design/src/main/scala/cache/BreezeCache.scala:128:            region.size % cacheConfig.ICACHE_LINE_BYTES == 0,
design/src/main/scala/cache/BreezeCache.scala:173:    val s1_tag_hit = Wire(Vec(cacheConfig.ICACHE_WAY_NUM, Bool()))
design/src/main/scala/cache/BreezeCache.scala:175:    val s1_dout = Wire(UInt(cacheConfig.FETCH_WIDTH.W))
design/src/main/scala/cache/BreezeCache.scala:180:    val tag_array = Seq.fill(cacheConfig.ICACHE_WAY_NUM)(Module(new flowSRAM(cacheConfig.ICACHE_SET_NUM, cacheConfig.ICACHE_TAG_WIDTH,"tag",inspectsram)))
design/src/main/scala/cache/BreezeCache.scala:181:    val data_array = Seq.fill(cacheConfig.ICACHE_WAY_NUM)(Module(new flowSRAM(cacheConfig.ICACHE_SET_NUM, cacheConfig.ICACHE_LINE_WIDTH,"data",inspectsram)))
design/src/main/scala/cache/BreezeCache.scala:182:    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
design/src/main/scala/cache/BreezeCache.scala:194:    val metaReg = RegInit(VecInit(Seq.fill(cacheConfig.ICACHE_SET_NUM)(0.U(cacheConfig.META_WIDTH.W)))) // PLRU, .....valid[1],valid[0]
design/src/main/scala/cache/BreezeCache.scala:196:    val snapshotTags = Reg(Vec(cacheConfig.ICACHE_WAY_NUM, UInt(cacheConfig.ICACHE_TAG_WIDTH.W)))
design/src/main/scala/cache/BreezeCache.scala:197:    val snapshotData = Reg(Vec(cacheConfig.ICACHE_WAY_NUM, UInt(cacheConfig.ICACHE_LINE_WIDTH.W)))
design/src/main/scala/cache/BreezeCache.scala:198:    val snapshotIndex = Reg(UInt(cacheConfig.ICACHE_INDEX_WIDTH.W))
design/src/main/scala/cache/BreezeCache.scala:204:    val earlyIndex = WireDefault(0.U(cacheConfig.ICACHE_INDEX_WIDTH.W))
design/src/main/scala/cache/BreezeCache.scala:207:    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
design/src/main/scala/cache/BreezeCache.scala:224:    val s1_word_offset = s1_vaddr(cacheConfig.ICACHE_LINE_OFFSET_WIDTH + cacheConfig.ICACHE_BYTES_OFFSET_WIDTH - 1, cacheConfig.ICACHE_BYTES_OFFSET_WIDTH)
design/src/main/scala/cache/BreezeCache.scala:225:    val s1_way_dout = Wire(Vec(cacheConfig.ICACHE_WAY_NUM, UInt(cacheConfig.FETCH_WIDTH.W)))
design/src/main/scala/cache/BreezeCache.scala:226:    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
design/src/main/scala/cache/BreezeCache.scala:227:        s1_way_dout(i) := data_array_rdata(i) >> (s1_word_offset * cacheConfig.FETCH_WIDTH.U)
design/src/main/scala/cache/BreezeCache.scala:230:    val desired_tag = s1_paddr(cacheConfig.PLEN - 1, cacheConfig.ICACHE_INDEX_WIDTH + cacheConfig.ICACHE_LINE_OFFSET_WIDTH + cacheConfig.ICACHE_BYTES_OFFSET_WIDTH)
design/src/main/scala/cache/BreezeCache.scala:231:    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
design/src/main/scala/cache/BreezeCache.scala:289:    line_addr := s2_paddr & ~((cacheConfig.ICACHE_LINE_BYTES - 1).U(cacheConfig.PLEN.W)) // cache line对齐
design/src/main/scala/cache/BreezeCache.scala:292:    val s2_replace_way = RegInit(0.U(log2Ceil(cacheConfig.ICACHE_WAY_NUM).W))
design/src/main/scala/cache/BreezeCache.scala:300:    val s2_refill_tag = Reg(UInt(cacheConfig.ICACHE_TAG_WIDTH.W))
design/src/main/scala/cache/BreezeCache.scala:305:        s2_refill_tag := s2_paddr(cacheConfig.PLEN - 1, cacheConfig.ICACHE_INDEX_WIDTH + cacheConfig.ICACHE_LINE_OFFSET_WIDTH + cacheConfig.ICACHE_BYTES_OFFSET_WIDTH)
design/src/main/scala/cache/BreezeCache.scala:310:    val s2_dout = RegInit(0.U(cacheConfig.FETCH_WIDTH.W))
design/src/main/scala/cache/BreezeCache.scala:345:    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
design/src/main/scala/cache/BreezeCache.scala:363:        s2_dout := io.next_level_rsp.data >> (s2_word_offset * cacheConfig.FETCH_WIDTH.U)
design/src/main/scala/cache/BreezeCache.scala:367:        for(i <- 0 until cacheConfig.ICACHE_SET_NUM){
design/src/main/scala/cache/BreezeCache.scala:380:    def index_pos(vaddr:UInt,cfg:DefaultICacheConfig): UInt = {
design/src/main/scala/cache/BreezeCache.scala:381:        vaddr(cfg.ICACHE_INDEX_WIDTH + cfg.ICACHE_LINE_OFFSET_WIDTH + cfg.ICACHE_BYTES_OFFSET_WIDTH - 1, cfg.ICACHE_LINE_OFFSET_WIDTH + cfg.ICACHE_BYTES_OFFSET_WIDTH)
design/src/main/scala/cache/BreezeCache.scala:433:        new BreezeCache(DefaultICacheConfig()),
design/src/main/scala/cache/BreezeDCache.scala:5:import flow.config.DefaultDCacheConfig
design/src/main/scala/cache/BreezeDCache.scala:71:    val cfg: DefaultDCacheConfig = DefaultDCacheConfig(),
design/src/main/scala/cache/BreezeDCache.scala:78:  private val ways = cfg.ways
design/src/main/scala/cache/BreezeDCache.scala:79:  private val sets = cfg.sets
design/src/main/scala/cache/BreezeDCache.scala:80:  private val wordsPerLine = cfg.lineBytes / 8
design/src/main/scala/cache/BreezeDCache.scala:87:  // offset=addr[4:0]; other legal DefaultDCacheConfig geometries would be
design/src/main/scala/cache/BreezeDCache.scala:89:  require(cfg.sets == 64 && cfg.lineBytes == 32,
design/src/main/scala/cache/BreezeDCache.scala:131:    val mmioReq = new DCacheMemReqIO(cfg.PLEN, cfg.lineBytes)
design/src/main/scala/cache/BreezeDCache.scala:132:    val mmioRsp = new DCacheMemRespIO(cfg.lineBytes)
design/src/main/scala/cache/BreezeDCache.scala:136:      val req = new BreezeCoherenceReqIO(plen, cfg.lineBytes, hartIdWidth, txnIdWidth)
design/src/main/scala/cache/BreezeDCache.scala:137:      val grant = Flipped(new BreezeCoherenceGrantIO(plen, cfg.lineBytes, hartIdWidth, txnIdWidth))
design/src/main/scala/cache/BreezeDCache.scala:139:      val probeResp = new BreezeCoherenceProbeRespIO(plen, cfg.lineBytes, hartIdWidth, txnIdWidth)
design/src/main/scala/cache/BreezeDCache.scala:150:  val tagArray = Seq.fill(ways)(Module(new flowSRAM(sets, cfg.tagWidth, "tag")))
design/src/main/scala/cache/BreezeDCache.scala:151:  val dataArray = Seq.fill(ways)(Module(new flowSRAM(sets, cfg.lineWidth, "data")))
design/src/main/scala/cache/BreezeDCache.scala:166:  val snapshotTags = Reg(Vec(ways, UInt(cfg.tagWidth.W)))
design/src/main/scala/cache/BreezeDCache.scala:167:  val snapshotData = Reg(Vec(ways, UInt(cfg.lineWidth.W)))
design/src/main/scala/cache/BreezeDCache.scala:190:  val victimWayReg = RegInit(0.U(cfg.wayIndexWidth.W))
design/src/main/scala/cache/BreezeDCache.scala:192:  val victimTagReg = RegInit(0.U(cfg.tagWidth.W))
design/src/main/scala/cache/BreezeDCache.scala:193:  val victimDataReg = RegInit(0.U(cfg.lineWidth.W))
design/src/main/scala/cache/BreezeDCache.scala:194:  val storeMergeReg = RegInit(0.U(cfg.lineWidth.W))
design/src/main/scala/cache/BreezeDCache.scala:197:  val amoLineReg = Reg(UInt(cfg.lineWidth.W))
design/src/main/scala/cache/BreezeDCache.scala:200:  val storeHitWayReg = RegInit(0.U(cfg.wayIndexWidth.W))
design/src/main/scala/cache/BreezeDCache.scala:203:  val upgradeLineReg = RegInit(0.U(cfg.lineWidth.W))
design/src/main/scala/cache/BreezeDCache.scala:208:  val refillLineReg = Reg(UInt(cfg.lineWidth.W))
design/src/main/scala/cache/BreezeDCache.scala:234:  val probeDataReg = RegInit(0.U(cfg.lineWidth.W))
design/src/main/scala/cache/BreezeDCache.scala:241:      address(cfg.lineOffsetWidth - 1, 3)
design/src/main/scala/cache/BreezeDCache.scala:251:      address(cfg.lineOffsetWidth - 1, 3)
design/src/main/scala/cache/BreezeDCache.scala:255:    val paddedMask = byteMask64.pad(cfg.lineWidth)
design/src/main/scala/cache/BreezeDCache.scala:256:    val paddedData = data.pad(cfg.lineWidth)
design/src/main/scala/cache/BreezeDCache.scala:257:    val shiftedMask = (paddedMask << shift)(cfg.lineWidth - 1, 0)
design/src/main/scala/cache/BreezeDCache.scala:258:    val shiftedData = (paddedData << shift)(cfg.lineWidth - 1, 0)
design/src/main/scala/cache/BreezeDCache.scala:671:      io.mmioReq.data := reqWData.pad(cfg.lineWidth)
design/src/main/scala/cache/BreezeDCache.scala:672:      io.mmioReq.mask := scalarMask.pad(cfg.lineBytes)
design/src/main/scala/cache/BreezeL2Home.scala:6:import flow.config.L2CacheGeometry
design/src/main/scala/cache/BreezeL2Home.scala:48:  * Frozen geometry from `L2CacheGeometry` (8 ways, 32 B lines, one bank). The
design/src/main/scala/cache/BreezeL2Home.scala:80:    val l2Cfg: L2CacheGeometry,
design/src/main/scala/cache/BreezeL2Home.scala:85:  private val ways = l2Cfg.ways
design/src/main/scala/cache/BreezeL2Home.scala:86:  private val sets = l2Cfg.sets
design/src/main/scala/cache/BreezeL2Home.scala:89:  private val lineBytes = l2Cfg.lineBytes
design/src/main/scala/cache/BreezeL2Home.scala:90:  private val lineWidth = l2Cfg.lineWidth
design/src/main/scala/cache/BreezeL2Home.scala:91:  private val setIndexWidth = l2Cfg.setIndexWidth
design/src/main/scala/cache/BreezeL2Home.scala:92:  private val wayIndexWidth = l2Cfg.wayIndexWidth
design/src/main/scala/cache/BreezeL2Home.scala:93:  private val lineOffsetWidth = l2Cfg.lineOffsetWidth
design/src/main/scala/cache/BreezeL2Home.scala:98:  require(l2Cfg.banks == 1, "L2/Home must be single-bank")
design/src/main/scala/config/config.scala:16:case class DefaultICacheConfig(
design/src/main/scala/config/config.scala:19:    ICACHE_LINE_BYTES:Int = 32, // cache line的字节数，默认是32B
design/src/main/scala/config/config.scala:20:    ICACHE_SET_NUM: Int = 64, // cache set的数量，默认是64
design/src/main/scala/config/config.scala:21:    ICACHE_WAY_NUM: Int = 4, // cache的路数，默认是4
design/src/main/scala/config/config.scala:22:    FETCH_WIDTH: Int = 32 // 从cache line中每次取出的指令位宽，默认是32bit = 4byte
design/src/main/scala/config/config.scala:24:    val ICACHE_LINE_WIDTH: Int = ICACHE_LINE_BYTES * 8 // cache line的位宽，默认是32byte = 32 * 8bit = 256 bits
design/src/main/scala/config/config.scala:25:    val ICACHE_BYTES_OFFSET_WIDTH = log2Ceil(FETCH_WIDTH / 8) // 地址将会是按照fetch width对齐
design/src/main/scala/config/config.scala:26:    val ICACHE_LINE_OFFSET_WIDTH = log2Ceil(ICACHE_LINE_BYTES) - ICACHE_BYTES_OFFSET_WIDTH // cache line内的偏移位宽
design/src/main/scala/config/config.scala:27:    val ICACHE_INDEX_WIDTH: Int = log2Ceil(ICACHE_SET_NUM) // cache set的索引位宽
design/src/main/scala/config/config.scala:28:    val ICACHE_TAG_WIDTH: Int = VLEN - ICACHE_INDEX_WIDTH - ICACHE_LINE_OFFSET_WIDTH - ICACHE_BYTES_OFFSET_WIDTH // cache tag的位宽
design/src/main/scala/config/config.scala:29:    val PLRU_WIDTH: Int = ICACHE_WAY_NUM - 1 // PLRU替换算法需要的位宽
design/src/main/scala/config/config.scala:30:    val META_WIDTH: Int = PLRU_WIDTH + ICACHE_WAY_NUM // 每个cache line需要存储的元信息位宽，包括valid位和PLRU位,选择valid放到低位
design/src/main/scala/config/config.scala:31:    assert(ICACHE_WAY_NUM == 4, "当前只支持4路组相连的cache")
design/src/main/scala/config/config.scala:67:    val cacheCfg: DefaultICacheConfig = DefaultICacheConfig(
design/src/main/scala/config/config.scala:111:case class DefaultDCacheConfig(
design/src/main/scala/config/config.scala:149:    val dcacheCapacityBytes: Int = 8192,
design/src/main/scala/config/config.scala:150:    val dcacheLineBytes: Int = 32,
design/src/main/scala/config/config.scala:151:    val dcacheWays: Int = 4,
design/src/main/scala/config/config.scala:184:    val dcacheCfg: DefaultDCacheConfig = DefaultDCacheConfig(
design/src/main/scala/config/config.scala:187:        capacityBytes = dcacheCapacityBytes,
design/src/main/scala/config/config.scala:188:        lineBytes = dcacheLineBytes,
design/src/main/scala/config/config.scala:189:        ways = dcacheWays
design/src/main/scala/config/config.scala:267:final case class L2CacheGeometry(
design/src/main/scala/config/config.scala:304:final case class BreezeClusterConfig(
design/src/main/scala/config/config.scala:320:    val l1i: DefaultICacheConfig = coreCfg().frontendCfg.cacheCfg
design/src/main/scala/config/config.scala:321:    val l1d: DefaultDCacheConfig = coreCfg().dcacheCfg
design/src/main/scala/config/config.scala:324:        l1i.ICACHE_SET_NUM * l1i.ICACHE_WAY_NUM * l1i.ICACHE_LINE_BYTES
design/src/main/scala/config/config.scala:325:    require(l1iCapacityBytes == l1d.capacityBytes &&
design/src/main/scala/config/config.scala:326:        l1i.ICACHE_LINE_BYTES == l1d.lineBytes &&
design/src/main/scala/config/config.scala:327:        l1i.ICACHE_WAY_NUM == l1d.ways,
design/src/main/scala/config/config.scala:331:    val l2: L2CacheGeometry =
design/src/main/scala/config/config.scala:332:        L2CacheGeometry(capacityBytes = numHarts * 2 * l1d.capacityBytes)
design/src/main/scala/config/config.scala:333:    require(l1d.lineBytes == l2.lineBytes,
design/src/main/scala/config/config.scala:334:        s"all cache levels must share lineBytes; L1=${l1d.lineBytes} L2=${l2.lineBytes}")
design/src/main/scala/config/config.scala:349:    val single: BreezeClusterConfig = BreezeClusterConfig("single", 1)
design/src/main/scala/config/config.scala:350:    val dual: BreezeClusterConfig = BreezeClusterConfig("dual", 2)
design/src/main/scala/config/config.scala:351:    val small: BreezeClusterConfig = BreezeClusterConfig("small", 4)
design/src/main/scala/config/config.scala:354:    def fromName(name: String): BreezeClusterConfig = name match {
design/src/main/scala/core/BreezeCore.scala:26:        val nextLevelRsp = new L1CacheMissRespIO(corecfg.frontendCfg.cacheCfg.ICACHE_LINE_WIDTH)
design/src/main/scala/core/BreezeCore.scala:89:    io.l1d <> backend.io.l1d
design/src/main/scala/core/common.scala:342:   val ICACHE_ACCESS    = 4
design/src/main/scala/core/common.scala:343:   val ICACHE_MISS      = 5
design/src/main/scala/frontend/BreezeFrontend.scala:99:        val nextLevelReq = new L1CacheMissReqIO(cfg.cacheCfg.PLEN)
design/src/main/scala/frontend/BreezeFrontend.scala:100:        val nextLevelRsp = new L1CacheMissRespIO(cfg.cacheCfg.ICACHE_LINE_WIDTH)
design/src/main/scala/frontend/BreezeFrontend.scala:109:    val icache = Module(new BreezeCache(cfg.cacheCfg, enabledebug = enabledebug, parallelLookup = cfg.enableMmu))
design/src/main/scala/interface/interface.scala:555:class BreezeCacheRespIO(val VLEN:Int = 64,val FETCH_WIDTH:Int = 32) extends Bundle{
design/src/main/scala/interface/interface.scala:556:    val data = UInt(FETCH_WIDTH.W)
design/src/main/scala/interface/interface.scala:582:class L1CacheMissRespIO(val ICACHE_LINE_WIDTH:Int = 256) extends Bundle {
design/src/main/scala/interface/interface.scala:583:    val data = Input(UInt(ICACHE_LINE_WIDTH.W))
design/src/main/scala/sim/BreezeCoreSimSupport.scala:346:            val cacheLineBytes = coreCfg.frontendCfg.cacheCfg.ICACHE_LINE_BYTES
design/src/main/scala/top/BreezeMulticoreClusterWishbone.scala:6:import flow.config.BreezeClusterConfig
design/src/main/scala/top/BreezeMulticoreClusterWishbone.scala:29:    val clusterCfg: BreezeClusterConfig,
design/src/main/scala/top/BreezeMulticoreClusterWishbone.scala:34:    private val numHarts = clusterCfg.numHarts
design/src/main/scala/top/BreezeMulticoreClusterWishbone.scala:62:    val l2Home = Module(new BreezeL2Home(clusterCfg.l2, numHarts, withCoherentDma = withCoherentDma))
design/src/main/scala/top/BreezeMulticoreClusterWishbone.scala:64:    val mmioArbiter = Module(new BreezeMmioArbiter(numHarts, clusterCfg.l1d.lineBytes))
design/src/main/scala/top/BreezeMulticoreClusterWishbone.scala:67:        lineBytes = clusterCfg.l1d.lineBytes,
design/src/main/scala/top/BreezeMulticoreClusterWishbone.scala:76:            coreCfg.dcacheCfg,
design/src/main/scala/top/BreezeMulticoreClusterWishbone.scala:78:            hartIdWidth = clusterCfg.hartIdWidth,
design/src/main/scala/top/BreezeMulticoreClusterWishbone.scala:79:            txnIdWidth = clusterCfg.txnIdWidth,
design/src/main/scala/top/GenerateBreezeMulticoreClusterWishbone.scala:39:        (clusterCfg.numHarts == 1 && privilegeProfile == PrivilegeProfile.Linux),
design/src/main/scala/top/GenerateBreezeMulticoreClusterWishbone.scala:60:        s"[BreezeCluster RTL] profile=${clusterCfg.profileName} harts=${clusterCfg.numHarts} " +
design/src/main/scala/top/GenerateBreezeMulticoreClusterWishbone.scala:111:    private val l1iBytes = clusterCfg.l1i.ICACHE_SET_NUM *
design/src/main/scala/top/GenerateBreezeMulticoreClusterWishbone.scala:112:        clusterCfg.l1i.ICACHE_WAY_NUM * clusterCfg.l1i.ICACHE_LINE_BYTES
design/src/main/scala/top/GenerateBreezeMulticoreClusterWishbone.scala:118:           |numHarts=${clusterCfg.numHarts}
design/src/main/scala/top/GenerateBreezeMulticoreClusterWishbone.scala:120:           |l1dBytes=${clusterCfg.l1d.capacityBytes}
design/src/main/scala/top/GenerateBreezeMulticoreClusterWishbone.scala:121:           |l2Bytes=${clusterCfg.l2.capacityBytes}
design/src/main/scala/top/GenerateBreezeMulticoreClusterWishbone.scala:122:           |lineBytes=${clusterCfg.l1d.lineBytes}
design/src/main/scala/top/GenerateBreezeMulticoreClusterWishbone.scala:123:           |l1Ways=${clusterCfg.l1d.ways}
design/src/main/scala/top/GenerateBreezeMulticoreClusterWishbone.scala:124:           |l2Ways=${clusterCfg.l2.ways}
design/src/test/scala/cache/BreezeCoherentDmaSpec.scala:6:import flow.config.L2CacheGeometry
design/src/test/scala/cache/BreezeCoherentDmaSpec.scala:15:  private val cfg = L2CacheGeometry(capacityBytes = 16384)
design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala:5:import flow.config.DefaultDCacheConfig
design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala:17:  private val cfg = DefaultDCacheConfig()
design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala:6:import flow.config.DefaultDCacheConfig
design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala:399:  private val cfg = DefaultDCacheConfig()
design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala:411:      new BreezeDCache(DefaultDCacheConfig(capacityBytes = 4096))
design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala:414:      new BreezeDCache(DefaultDCacheConfig(lineBytes = 64))
design/src/test/scala/cache/BreezeL2HomeSmallSpec.scala:505:  // sets, single bank (BreezeClusterConfig("small", 4).l2).
design/src/test/scala/cache/BreezeL2HomeSmallSpec.scala:507:  private val smallL2Cfg = smallCfg.l2
design/src/test/scala/cache/BreezeL2HomeSmallSpec.scala:508:  private val numHarts = smallCfg.numHarts
design/src/test/scala/cache/BreezeL2HomeSpec.scala:6:import flow.config.L2CacheGeometry
design/src/test/scala/cache/BreezeL2HomeSpec.scala:402:  private val l2Cfg = L2CacheGeometry(capacityBytes = 16384)
design/src/test/scala/cache/BreezeL2HomeSpec.scala:403:  private val dualL2Cfg = L2CacheGeometry(capacityBytes = 32768)
design/src/test/scala/cache/BreezeParallelLookupSpec.scala:5:import flow.config.{DefaultDCacheConfig, DefaultICacheConfig}
design/src/test/scala/cache/BreezeParallelLookupSpec.scala:11:class ParallelDCacheHarness extends BreezeDCache(DefaultDCacheConfig(), parallelLookup = true) {
design/src/test/scala/cache/BreezeParallelLookupSpec.scala:22:class ParallelICacheHarness extends BreezeCache(DefaultICacheConfig(), parallelLookup = true) {
design/src/test/scala/cache/breezecacheSpec.scala:8:import flow.config.DefaultICacheConfig
design/src/test/scala/cache/breezecacheSpec.scala:64:    val cfg = DefaultICacheConfig()
design/src/test/scala/cache/breezecacheSpec.scala:66:    class refCache(val cacheConfig: DefaultICacheConfig){
design/src/test/scala/cache/breezecacheSpec.scala:141:            val dataArrayWeIdx = (0 until cfg.ICACHE_WAY_NUM).filter(i => ((dataArrayWe >> i) & 1) == 1)
design/src/test/scala/cache/breezecacheSpec.scala:142:            val tagArrayWeIdx = (0 until cfg.ICACHE_WAY_NUM).filter(i => ((tagArrayWe >> i) & 1) == 1)
design/src/test/scala/config/BreezeCoreConfigSpec.scala:69:        single.numHarts mustBe 1
design/src/test/scala/config/BreezeCoreConfigSpec.scala:70:        single.hartIdWidth mustBe 1
design/src/test/scala/config/BreezeCoreConfigSpec.scala:71:        single.sharerWidth mustBe 1
design/src/test/scala/config/BreezeCoreConfigSpec.scala:72:        single.txnIdWidth mustBe 2
design/src/test/scala/config/BreezeCoreConfigSpec.scala:75:        single.l1d.capacityBytes mustBe 8192
design/src/test/scala/config/BreezeCoreConfigSpec.scala:76:        single.l1d.lineBytes mustBe 32
design/src/test/scala/config/BreezeCoreConfigSpec.scala:77:        single.l1d.ways mustBe 4
design/src/test/scala/config/BreezeCoreConfigSpec.scala:78:        single.l1d.sets mustBe 64
design/src/test/scala/config/BreezeCoreConfigSpec.scala:79:        single.l1d mustBe single.coreCfg().dcacheCfg
design/src/test/scala/config/BreezeCoreConfigSpec.scala:81:        single.l1i.ICACHE_SET_NUM mustBe 64
design/src/test/scala/config/BreezeCoreConfigSpec.scala:82:        single.l1i.ICACHE_WAY_NUM mustBe 4
design/src/test/scala/config/BreezeCoreConfigSpec.scala:83:        single.l1i.ICACHE_LINE_BYTES mustBe 32
design/src/test/scala/config/BreezeCoreConfigSpec.scala:84:        single.l2.capacityBytes mustBe 16384
design/src/test/scala/config/BreezeCoreConfigSpec.scala:85:        single.l2.ways mustBe 8
design/src/test/scala/config/BreezeCoreConfigSpec.scala:86:        single.l2.sets mustBe 64
design/src/test/scala/config/BreezeCoreConfigSpec.scala:87:        single.l2.lineBytes mustBe 32
design/src/test/scala/config/BreezeCoreConfigSpec.scala:90:        dual.numHarts mustBe 2
design/src/test/scala/config/BreezeCoreConfigSpec.scala:91:        dual.hartIdWidth mustBe 1
design/src/test/scala/config/BreezeCoreConfigSpec.scala:92:        dual.sharerWidth mustBe 2
design/src/test/scala/config/BreezeCoreConfigSpec.scala:93:        dual.l2.capacityBytes mustBe 32768
design/src/test/scala/config/BreezeCoreConfigSpec.scala:94:        dual.l2.sets mustBe 128
design/src/test/scala/config/BreezeCoreConfigSpec.scala:98:        small.numHarts mustBe 4
design/src/test/scala/config/BreezeCoreConfigSpec.scala:99:        small.hartIdWidth mustBe 2
design/src/test/scala/config/BreezeCoreConfigSpec.scala:100:        small.sharerWidth mustBe 4
design/src/test/scala/config/BreezeCoreConfigSpec.scala:101:        small.l2.capacityBytes mustBe 65536
design/src/test/scala/config/BreezeCoreConfigSpec.scala:102:        small.l2.sets mustBe 256
design/src/test/scala/config/BreezeCoreConfigSpec.scala:110:        dualBaseline.numHarts mustBe 2
design/src/test/scala/config/BreezeCoreConfigSpec.scala:111:        dualBaseline.l2.capacityBytes mustBe 32768
design/src/test/scala/config/BreezeCoreConfigSpec.scala:112:        dualBaseline.l1d mustBe BreezeClusterPresets.dual.l1d
design/src/test/scala/config/BreezeCoreConfigSpec.scala:117:            BreezeClusterConfig(profileName = "standard", numHarts = 8)
design/src/test/scala/config/BreezeCoreConfigSpec.scala:119:            BreezeClusterConfig(profileName = "max", numHarts = 16)
design/src/test/scala/fase/FaseIntegrationHarness.scala:5:import flow.config.BreezeClusterConfig
design/src/test/scala/fase/FaseIntegrationHarness.scala:30:class FaseIntegrationHarness(cfg: BreezeClusterConfig, serial: Boolean) extends Module {
```

### 当前仍需后续配置归并的引用

新 L1IParams 中同名派生属性直接来自 BreezeMemGeometry，属于消费端迁移后的名称；config.scala 的旧类、前端边界宽度、standalone Core 与仿真行宽、BreezeCoreConfigSpec 仍保留。本轮仅清单，后续修改必须同步这些调用点与默认值合同。

```text
design/src/main/scala/config/BreezeMemConfig.scala:8:  * backend work in config.scala. At merge, BreezeClusterConfig gains
design/src/main/scala/config/config.scala:16:case class DefaultICacheConfig(
design/src/main/scala/config/config.scala:19:    ICACHE_LINE_BYTES:Int = 32, // cache line的字节数，默认是32B
design/src/main/scala/config/config.scala:20:    ICACHE_SET_NUM: Int = 64, // cache set的数量，默认是64
design/src/main/scala/config/config.scala:21:    ICACHE_WAY_NUM: Int = 4, // cache的路数，默认是4
design/src/main/scala/config/config.scala:22:    FETCH_WIDTH: Int = 32 // 从cache line中每次取出的指令位宽，默认是32bit = 4byte
design/src/main/scala/config/config.scala:24:    val ICACHE_LINE_WIDTH: Int = ICACHE_LINE_BYTES * 8 // cache line的位宽，默认是32byte = 32 * 8bit = 256 bits
design/src/main/scala/config/config.scala:25:    val ICACHE_BYTES_OFFSET_WIDTH = log2Ceil(FETCH_WIDTH / 8) // 地址将会是按照fetch width对齐
design/src/main/scala/config/config.scala:26:    val ICACHE_LINE_OFFSET_WIDTH = log2Ceil(ICACHE_LINE_BYTES) - ICACHE_BYTES_OFFSET_WIDTH // cache line内的偏移位宽
design/src/main/scala/config/config.scala:27:    val ICACHE_INDEX_WIDTH: Int = log2Ceil(ICACHE_SET_NUM) // cache set的索引位宽
design/src/main/scala/config/config.scala:28:    val ICACHE_TAG_WIDTH: Int = VLEN - ICACHE_INDEX_WIDTH - ICACHE_LINE_OFFSET_WIDTH - ICACHE_BYTES_OFFSET_WIDTH // cache tag的位宽
design/src/main/scala/config/config.scala:29:    val PLRU_WIDTH: Int = ICACHE_WAY_NUM - 1 // PLRU替换算法需要的位宽
design/src/main/scala/config/config.scala:30:    val META_WIDTH: Int = PLRU_WIDTH + ICACHE_WAY_NUM // 每个cache line需要存储的元信息位宽，包括valid位和PLRU位,选择valid放到低位
design/src/main/scala/config/config.scala:31:    assert(ICACHE_WAY_NUM == 4, "当前只支持4路组相连的cache")
design/src/main/scala/config/config.scala:67:    val cacheCfg: DefaultICacheConfig = DefaultICacheConfig(
design/src/main/scala/config/config.scala:111:case class DefaultDCacheConfig(
design/src/main/scala/config/config.scala:149:    val dcacheCapacityBytes: Int = 8192,
design/src/main/scala/config/config.scala:150:    val dcacheLineBytes: Int = 32,
design/src/main/scala/config/config.scala:151:    val dcacheWays: Int = 4,
design/src/main/scala/config/config.scala:184:    val dcacheCfg: DefaultDCacheConfig = DefaultDCacheConfig(
design/src/main/scala/config/config.scala:187:        capacityBytes = dcacheCapacityBytes,
design/src/main/scala/config/config.scala:188:        lineBytes = dcacheLineBytes,
design/src/main/scala/config/config.scala:189:        ways = dcacheWays
design/src/main/scala/config/config.scala:267:final case class L2CacheGeometry(
design/src/main/scala/config/config.scala:304:final case class BreezeClusterConfig(
design/src/main/scala/config/config.scala:320:    val l1i: DefaultICacheConfig = coreCfg().frontendCfg.cacheCfg
design/src/main/scala/config/config.scala:321:    val l1d: DefaultDCacheConfig = coreCfg().dcacheCfg
design/src/main/scala/config/config.scala:324:        l1i.ICACHE_SET_NUM * l1i.ICACHE_WAY_NUM * l1i.ICACHE_LINE_BYTES
design/src/main/scala/config/config.scala:326:        l1i.ICACHE_LINE_BYTES == l1d.lineBytes &&
design/src/main/scala/config/config.scala:327:        l1i.ICACHE_WAY_NUM == l1d.ways,
design/src/main/scala/config/config.scala:331:    val l2: L2CacheGeometry =
design/src/main/scala/config/config.scala:332:        L2CacheGeometry(capacityBytes = numHarts * 2 * l1d.capacityBytes)
design/src/main/scala/config/config.scala:349:    val single: BreezeClusterConfig = BreezeClusterConfig("single", 1)
design/src/main/scala/config/config.scala:350:    val dual: BreezeClusterConfig = BreezeClusterConfig("dual", 2)
design/src/main/scala/config/config.scala:351:    val small: BreezeClusterConfig = BreezeClusterConfig("small", 4)
design/src/main/scala/config/config.scala:354:    def fromName(name: String): BreezeClusterConfig = name match {
design/src/main/scala/core/BreezeCore.scala:26:        val nextLevelRsp = new L1CacheMissRespIO(corecfg.frontendCfg.cacheCfg.ICACHE_LINE_WIDTH)
design/src/main/scala/core/BreezePerformanceCounters.scala:57:        BREEZE_HPM_EVENT.ICACHE_ACCESS -> io.events.icacheAccess,
design/src/main/scala/core/BreezePerformanceCounters.scala:58:        BREEZE_HPM_EVENT.ICACHE_MISS -> io.events.icacheMiss,
design/src/main/scala/core/common.scala:342:   val ICACHE_ACCESS    = 4
design/src/main/scala/core/common.scala:343:   val ICACHE_MISS      = 5
design/src/main/scala/frontend/BreezeFrontend.scala:101:        val nextLevelReq = new L1CacheMissReqIO(cfg.cacheCfg.PLEN)
design/src/main/scala/frontend/BreezeFrontend.scala:102:        val nextLevelRsp = new L1CacheMissRespIO(cfg.cacheCfg.ICACHE_LINE_WIDTH)
design/src/main/scala/interface/interface.scala:555:class BreezeCacheRespIO(val VLEN:Int = 64,val FETCH_WIDTH:Int = 32) extends Bundle{
design/src/main/scala/interface/interface.scala:556:    val data = UInt(FETCH_WIDTH.W)
design/src/main/scala/interface/interface.scala:582:class L1CacheMissRespIO(val ICACHE_LINE_WIDTH:Int = 256) extends Bundle {
design/src/main/scala/interface/interface.scala:583:    val data = Input(UInt(ICACHE_LINE_WIDTH.W))
design/src/main/scala/l1i/L1ICache.scala:18:    val s1_meta = Output(UInt(cacheConfig.META_WIDTH.W))
design/src/main/scala/l1i/L1ICache.scala:19:    val s1_tag_hit = Output(UInt(cacheConfig.ICACHE_WAY_NUM.W))
design/src/main/scala/l1i/L1ICache.scala:27:    val data_array_we = Output(UInt(cacheConfig.ICACHE_WAY_NUM.W))
design/src/main/scala/l1i/L1ICache.scala:28:    val tag_array_we = Output(UInt(cacheConfig.ICACHE_WAY_NUM.W))
design/src/main/scala/l1i/L1ICache.scala:43:        val drsp = Decoupled(new BreezeCacheRespIO(cacheConfig.VLEN,cacheConfig.FETCH_WIDTH))
design/src/main/scala/l1i/L1ICache.scala:46:        val next_level_rsp = new L1CacheMissRespIO(cacheConfig.ICACHE_LINE_WIDTH)
design/src/main/scala/l1i/L1ICache.scala:52:    require(!parallelLookup || cacheConfig.ICACHE_SET_NUM * cacheConfig.ICACHE_LINE_BYTES <= 4096,
design/src/main/scala/l1i/L1ICache.scala:57:            region.origin % cacheConfig.ICACHE_LINE_BYTES == 0 &&
design/src/main/scala/l1i/L1ICache.scala:58:            region.size % cacheConfig.ICACHE_LINE_BYTES == 0,
design/src/main/scala/l1i/L1ICache.scala:103:    val s1_tag_hit = Wire(Vec(cacheConfig.ICACHE_WAY_NUM, Bool()))
design/src/main/scala/l1i/L1ICache.scala:105:    val s1_dout = Wire(UInt(cacheConfig.FETCH_WIDTH.W))
design/src/main/scala/l1i/L1ICache.scala:110:    val tag_array = Seq.fill(cacheConfig.ICACHE_WAY_NUM)(Module(new flowSRAM(cacheConfig.ICACHE_SET_NUM, cacheConfig.ICACHE_TAG_WIDTH,"tag",inspectsram)))
design/src/main/scala/l1i/L1ICache.scala:111:    val data_array = Seq.fill(cacheConfig.ICACHE_WAY_NUM)(Module(new flowSRAM(cacheConfig.ICACHE_SET_NUM, cacheConfig.ICACHE_LINE_WIDTH,"data",inspectsram)))
design/src/main/scala/l1i/L1ICache.scala:112:    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
design/src/main/scala/l1i/L1ICache.scala:124:    val metaReg = RegInit(VecInit(Seq.fill(cacheConfig.ICACHE_SET_NUM)(0.U(cacheConfig.META_WIDTH.W)))) // PLRU, .....valid[1],valid[0]
design/src/main/scala/l1i/L1ICache.scala:126:    val snapshotTags = Reg(Vec(cacheConfig.ICACHE_WAY_NUM, UInt(cacheConfig.ICACHE_TAG_WIDTH.W)))
design/src/main/scala/l1i/L1ICache.scala:127:    val snapshotData = Reg(Vec(cacheConfig.ICACHE_WAY_NUM, UInt(cacheConfig.ICACHE_LINE_WIDTH.W)))
design/src/main/scala/l1i/L1ICache.scala:128:    val snapshotIndex = Reg(UInt(cacheConfig.ICACHE_INDEX_WIDTH.W))
design/src/main/scala/l1i/L1ICache.scala:134:    val earlyIndex = WireDefault(0.U(cacheConfig.ICACHE_INDEX_WIDTH.W))
design/src/main/scala/l1i/L1ICache.scala:137:    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
design/src/main/scala/l1i/L1ICache.scala:154:    val s1_word_offset = s1_vaddr(cacheConfig.ICACHE_LINE_OFFSET_WIDTH + cacheConfig.ICACHE_BYTES_OFFSET_WIDTH - 1, cacheConfig.ICACHE_BYTES_OFFSET_WIDTH)
design/src/main/scala/l1i/L1ICache.scala:155:    val s1_way_dout = Wire(Vec(cacheConfig.ICACHE_WAY_NUM, UInt(cacheConfig.FETCH_WIDTH.W)))
design/src/main/scala/l1i/L1ICache.scala:156:    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
design/src/main/scala/l1i/L1ICache.scala:157:        s1_way_dout(i) := data_array_rdata(i) >> (s1_word_offset * cacheConfig.FETCH_WIDTH.U)
design/src/main/scala/l1i/L1ICache.scala:160:    val desired_tag = s1_paddr(cacheConfig.PLEN - 1, cacheConfig.ICACHE_INDEX_WIDTH + cacheConfig.ICACHE_LINE_OFFSET_WIDTH + cacheConfig.ICACHE_BYTES_OFFSET_WIDTH)
design/src/main/scala/l1i/L1ICache.scala:161:    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
design/src/main/scala/l1i/L1ICache.scala:219:    line_addr := s2_paddr & ~((cacheConfig.ICACHE_LINE_BYTES - 1).U(cacheConfig.PLEN.W)) // cache line对齐
design/src/main/scala/l1i/L1ICache.scala:224:    val ways = cacheConfig.ICACHE_WAY_NUM
design/src/main/scala/l1i/L1ICache.scala:226:    val plruState = if (ways == 1) 0.U(0.W) else s2_entry_meta(cacheConfig.META_WIDTH-1, ways)
design/src/main/scala/l1i/L1ICache.scala:232:    val s2_new_valid_vec = Reg(UInt(cacheConfig.ICACHE_WAY_NUM.W))
design/src/main/scala/l1i/L1ICache.scala:233:    val s2_new_plru_vec = Reg(UInt(cacheConfig.PLRU_WIDTH.W))
design/src/main/scala/l1i/L1ICache.scala:234:    val s2_wt_en_OH = Reg(UInt(cacheConfig.ICACHE_WAY_NUM.W))
design/src/main/scala/l1i/L1ICache.scala:235:    val s2_refill_tag = Reg(UInt(cacheConfig.ICACHE_TAG_WIDTH.W))
design/src/main/scala/l1i/L1ICache.scala:240:        s2_refill_tag := s2_paddr(cacheConfig.PLEN - 1, cacheConfig.ICACHE_INDEX_WIDTH + cacheConfig.ICACHE_LINE_OFFSET_WIDTH + cacheConfig.ICACHE_BYTES_OFFSET_WIDTH)
design/src/main/scala/l1i/L1ICache.scala:245:    val s2_dout = RegInit(0.U(cacheConfig.FETCH_WIDTH.W))
design/src/main/scala/l1i/L1ICache.scala:280:    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
design/src/main/scala/l1i/L1ICache.scala:298:        s2_dout := io.next_level_rsp.data >> (s2_word_offset * cacheConfig.FETCH_WIDTH.U)
design/src/main/scala/l1i/L1ICache.scala:302:        for(i <- 0 until cacheConfig.ICACHE_SET_NUM){
design/src/main/scala/l1i/L1ICache.scala:316:        if (cfg.ICACHE_SET_NUM == 1) 0.U(1.W)
design/src/main/scala/l1i/L1ICache.scala:317:        else vaddr(cfg.ICACHE_INDEX_WIDTH + cfg.ICACHE_LINE_OFFSET_WIDTH + cfg.ICACHE_BYTES_OFFSET_WIDTH - 1, cfg.ICACHE_LINE_OFFSET_WIDTH + cfg.ICACHE_BYTES_OFFSET_WIDTH)
design/src/main/scala/l1i/L1IParams.scala:10:  val FETCH_WIDTH = 32
design/src/main/scala/l1i/L1IParams.scala:11:  val ICACHE_LINE_BYTES = g.lineBytes
design/src/main/scala/l1i/L1IParams.scala:12:  val ICACHE_SET_NUM = g.l1Sets
design/src/main/scala/l1i/L1IParams.scala:13:  val ICACHE_WAY_NUM = g.l1iWays
design/src/main/scala/l1i/L1IParams.scala:14:  val ICACHE_LINE_WIDTH = g.lineBytes * 8
design/src/main/scala/l1i/L1IParams.scala:15:  val ICACHE_BYTES_OFFSET_WIDTH = log2Ceil(FETCH_WIDTH / 8)
design/src/main/scala/l1i/L1IParams.scala:16:  val ICACHE_LINE_OFFSET_WIDTH = log2Ceil(g.lineBytes) - ICACHE_BYTES_OFFSET_WIDTH
design/src/main/scala/l1i/L1IParams.scala:17:  val ICACHE_INDEX_WIDTH = log2Ceil(g.l1Sets)
design/src/main/scala/l1i/L1IParams.scala:18:  val ICACHE_TAG_WIDTH = PLEN - log2Ceil(g.lineBytes) - ICACHE_INDEX_WIDTH
design/src/main/scala/l1i/L1IParams.scala:19:  val PLRU_WIDTH = g.l1iWays - 1
design/src/main/scala/l1i/L1IParams.scala:20:  val META_WIDTH = PLRU_WIDTH + g.l1iWays
design/src/main/scala/sim/BreezeCoreSimSupport.scala:347:            val cacheLineBytes = coreCfg.frontendCfg.cacheCfg.ICACHE_LINE_BYTES
design/src/test/scala/cache/L1ICacheSpec.scala:84:            val dataArrayWeIdx = (0 until cfg.ICACHE_WAY_NUM).filter(i => ((dataArrayWe >> i) & 1) == 1)
design/src/test/scala/cache/L1ICacheSpec.scala:85:            val tagArrayWeIdx = (0 until cfg.ICACHE_WAY_NUM).filter(i => ((tagArrayWe >> i) & 1) == 1)
design/src/test/scala/config/BreezeCoreConfigSpec.scala:79:        single.l1d mustBe single.coreCfg().dcacheCfg
design/src/test/scala/config/BreezeCoreConfigSpec.scala:81:        single.l1i.ICACHE_SET_NUM mustBe 64
design/src/test/scala/config/BreezeCoreConfigSpec.scala:82:        single.l1i.ICACHE_WAY_NUM mustBe 4
design/src/test/scala/config/BreezeCoreConfigSpec.scala:83:        single.l1i.ICACHE_LINE_BYTES mustBe 32
design/src/test/scala/config/BreezeCoreConfigSpec.scala:117:            BreezeClusterConfig(profileName = "standard", numHarts = 8)
design/src/test/scala/config/BreezeCoreConfigSpec.scala:119:            BreezeClusterConfig(profileName = "max", numHarts = 16)
```

其余程序期望同步：高 Bare user fetch 的错误地址通过 handler 读 mepc=完整 64-bit target、mcause=1 验证，该故障取指不产生 commit。非法 CSR probe 的 mtval 按当前 Backend 在 MEM/WB 的 rawInst 值 `0xfb0024f3` 精确检查，旧测试硬编码 0 已不匹配；不修改 RTL。Core ecall 的“一次”改为统计 trap pulse=1、正常 commit=0，而非删掉一次性检查。

原生 CPU 端口地址补正：`backend/BreezeBackend.scala:277` 之前无条件 rs1+imm，会把 AMO funct5/rs2 位加为偏移；Decoder `InstDecode.scala:356` 已明确 RV64A 地址 rs1/CONST0。本轮对 LR/SC/AMO offset 置零，不改 cache 的原子执行；native BackendBehaviorSpec 分别检查 LR、SC、AMO 请求保持 rs1，以及紧邻 CSR→SC 的 wdata 和零基址。高 user-PC 程序的 SC 曾因此错误触发 cause=6，保留该程序验收作为端口修复回归。
