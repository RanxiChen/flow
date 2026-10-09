# Breeze v1 SoC 边界与 FPGA 上板 spec（冻结）

本文冻结 v1 集群接入 LiteX/KCU105 的边界。用户决定（2026-10-07）：

- 不做 OpenSBI/Linux 全系统仿真，直接上 FPGA，用 ILA 边跑边看；上板前只保留分钟级的连线冒烟仿真。
- 集群与 SoC 之间丢弃 Wishbone，内存口用 AXI 直连 LiteDRAM。
- FASE 本轮不做（接口待核稳定后另行冻结），PCIe 不做，SD 卡 DMA 本轮不做。

本文只规定边界、路由、调试与门槛；实现细节由执行者决定并在报告中写明。下文「必须」条目是冻结合同，不得为通过测试而改变；确有问题时停止并报告，由 Claude/用户裁定。

## 1. 集群 RTL 边界（不改）

`flow.top.BreezeCluster`（`design/src/main/scala/top/BreezeCluster.scala`）的端口和行为保持不变：

| 端口 | 类型 | 说明 |
| --- | --- | --- |
| `io.mem` | AXI4 主口：地址 32 bit、数据 64 bit、ID `slotBits` bit，INCR burst，一行 32 B = 4 拍 | 所有可缓存区域（boot_rom、linux_boot_rom、sram、main_ram）的回填与写回 |
| `io.mmio` | AXI4-Lite 主口，64 bit | 所有不可缓存设备访问（CLINT、PLIC、LiteX CSR 窗口） |
| `io.dma` | `ReadClientIO` 从侧 | 本轮固定不使用：req.valid=0，rspDown.ready=1 |
| 中断/时间/复位地址/调试输出 | 同现有 | |

必须：

- C1 不修改 L1D、L2/Home、后端、MMU、前端的行为来适配 SoC。若上板前测试暴露这些模块的 bug，保存证据、单独提交修复，并在报告中列出；不得通过 SoC 侧绕开（例如在路由里吞掉响应、改顺序）。SOC-3d 后续时序优化例外（2026-10-09 用户明确授权）：L1IClient demand/prefetch 返回 payload/error/valid 增加一拍寄存边界，保持每拍可接收一条 RSPdown，命中取指不加拍，refill 返回增加一拍。已接受协议响应不因 redirect/flush 取消，I-cache 原 flush 跟踪决定安装/交付；本地高 PA fault 也经过该边界。D-cache 命中、WB/W2 与 PTW 状态拍数保持。
- C2 `L2MemEngine` 断言 R 通道**按 AR 发出顺序**返回（跨 ID 也是）。SoC 侧必须满足这个顺序（见 §3 R1），不得放宽或删除该断言。
- C3 参数只来自 `BreezeClusterConfig`（profile `single` 与 `small`），不另设第二套几何参数。

## 2. 新顶层与生成入口

- 删除 `BreezeClusterWishbone`、`bus/Axi4WishboneBridge.scala`、`bus/DmaWishboneClient.scala` 及只服务它们的测试；`MemSkeletonElabSpec`、`MemoryBridgeSpec` 中引用这些类的用例改为针对新顶层或删除，并在报告中逐条列出删除/改写的用例和理由。
- 新增 Chisel 顶层 `BreezeClusterAxi(cfg, enableTandem, debug)`：
  - 直接引出集群 `io.mem`（AXI4）与 `io.mmio`（AXI4-Lite），端口命名稳定，供 LiteX `Instance` 连接；
  - 不含任何总线协议转换；
  - `debug=true` 时额外引出 §4 的调试信号；`debug=false` 时不引出（生产版不带空探针）。
- 生成器沿用 `flow.top.GenerateBreezeCluster`，参数：`<single|small> <gshare|baseline> <mcu|linux> [tandem] [fpga-debug]`。输出目录、`filelist.f`、`cluster-profile.txt` 的格式由执行者决定，但 marker 必须包含 `bus=axi`、profile、preset、privilege、tandem、debug、platformSha256，LiteX 侧逐项校验，不匹配即报错。
- `dma`、FASE 选项本轮从生成器移除；传入时报错并提示「未支持」。

## 3. SoC 路由（LiteX 侧）

LiteX SoC 内部总线仍可用 Wishbone（BIOS ROM、SRAM、CSR 都是 LiteX 自带），但**集群端口一律是 AXI**。

`io.mem` 经一个 SoC 侧 AXI 路由器分两路：

| 地址 | 去向 | 要求 |
| --- | --- | --- |
| main_ram（`0x8000_0000`，2 GiB，取自 `config/breeze_mcu_platform.json`） | LiteDRAM crossbar 的 AXI 端口（LiteDRAM 自带的 AXI→native 转换，含必要的位宽转换） | 保留 INCR burst，不拆成单拍 |
| boot_rom、linux_boot_rom、sram | LiteX 主总线（用 LiteX 自带 AXI→Wishbone 转换） | 低带宽可接受 |
| 其他 | 路由器本地返回 DECERR | 不得挂死 |

`io.mmio` 经 LiteX 自带 AXI-Lite→Wishbone 接到主总线；CLINT/PLIC/LiteX CSR 的地址不变（`core.py` 的 `io_regions`）。

必须：

- R1 读顺序：路由器保证 R 按 AR 接受顺序返回，跨两路也是。最简单的合格实现：有读在途时，不向另一路发出新 AR（写通道同理，按 AW 顺序返回 B）。若改用重排序缓冲，要能证明满足同样性质。
- R2 W 数据跟随其 AW 的去向，不得错路；B/R 的 resp 原样传回（错误不被吞掉）。
- R3 路由按 burst 首地址决定；一行 32 B 对齐访问不会跨区域，路由器对跨区域 burst 返回错误而不是拆分。
- R4 地址表只从 `config/breeze_mcu_platform.json` 读取（`target.py` 已有 sram 一致性检查，扩展到 main_ram/rom），不手写第二份。

`core.py`：`_BreezeClusterCPU` 改为 AXI：`memory_bus`（AXI4）只接上述路由器，`mmio_bus`（AXI-Lite）挂主总线；删除 Wishbone 接口、`dma_bus` 与 FASE/Dma 产品类。保留 `Breeze`（small，4 hart）、`BreezeTiny`（single）、`BreezeTinyDebug`。复位地址、mem_map、CLINT/PLIC 不变。

## 4. 调试（ILA，不含 FASE）

`BreezeTinyDebug`（`--debug`，只允许单核）沿用现有 `flow/ila.py` 的结构，探针换成新集群的信号：

- hart0 退休记录全部字段；最后退休 PC/指令、`seen_retire`、饱和 `no_retire_cycles`（系统周期）；
- hart fatal/estop、低位 MTIME；
- `io.mem` 五个通道的 valid/ready、AR/AW 的 addr/id/len、R/B 的 id/resp/last、W 的 strb/last，R/W 数据低 64 bit；
- 路由器两路各自的读/写在途计数；
- `io.mmio` 五通道握手、地址、数据、resp；
- `l1dEvents(0)`、`l2Events`。

挂死检测（`debug=true` 时在 Chisel 顶层实现，生产版不含）：

- H1 `hang` 粘滞位：`no_retire_cycles` 达到阈值，或 `io.mem`/`io.mmio` 任一通道 valid&&!ready 连续达到阈值，或任一通道请求已发出而响应超时。阈值为生成参数，默认按 100 MHz 约 0.1 s；
- H2 `hang` 和各条件的原因位作为 ILA 探针，供触发使用，并接一个板上 LED；
- H3 只观察，不改变任何握手。

`ila-probes.json` 继续输出探针名与位宽，与 `.ltx` 同次生成。

## 5. 冒烟仿真（上板前，分钟级）

目的只是查连线：地址映射、AXI 位宽/ID、复位、路由顺序。不跑 OpenSBI/Linux。

- 在 `docs/cross-project/simulation-host.md` 选定的主机上（先 cloud_chen，不可用再 Alan）用 Verilator 跑 LiteX 仿真：与 FPGA 相同的 CPU 包装和 §3 路由，DRAM 用 LiteDRAM `SDRAMPHYModel`。
- S1 通过条件：BIOS 横幅出现在 UART；BIOS 从 boot ROM 取指、用 sram 作栈；main_ram 的 memtest（≥ 64 KiB）通过；运行中 `L2MemEngine` 等所有断言不触发。
- S2 single 与 small 两个 profile 都要过；small 时其余 hart 按现有启动代码停驻。
- S3 每次仿真墙钟上限 30 min；超时视为失败并保存日志，不加长时间凑通过。

## 6. FPGA 构建（Alan，Vivado）

- 顺序：`breeze-tiny` 生产版 100 MHz → `breeze-tiny --debug` 100 MHz → `breeze`（4 hart）生产版。
- 每次记录：SHA、命令、输出目录、WNS/TNS/WHS、LUT/FF/BRAM/DSP 利用率、bitstream 与 `.ltx` 路径。
- WNS < 0 不算构建失败，但要列出最差 10 条路径的起止点；只有路径落在本任务新增的 SoC 胶合逻辑（路由器、挂死检测、包装）时才在本任务内修。核内路径只报告。
- 上板由用户本人进行；执行者停在 bitstream 产出。

## 7. 本轮不做

FASE、飞行记录器、性能计数器 `perf` 从口、SD 卡/DMA、PCIe、OpenSBI/Linux 仿真、频率优化。


## 2026-10-09 SOC-3e 用户授权流水边界修订

本节对应用户批准的组合优化，并优先于上文 SOC-3d 和 v1 的旧级数、旧拍数及“不得新增级/输出缓存”限制。架构数值、异常、PMP/PMA 权限与副作用规则不变；本节明确列出的流水延迟是本轮批准的合同迁移，不能以此放宽 golden、种子、规模或 watchdog。实现与证据见 `tasks/SOC-3e-pipeline-report.md`，未取得的仿真/最终布线/上板证据不得沿用基线结论。

L1D CPU 与内部完成流水均为 S0 接收、S1 捕获翻译及同步阵列输出、S2 并行判断寄存 PA 的 PMP/PMA 与 tag/data、S3 最终响应/分配/PS 接收。S0 的 dTLB 和 VIPT SRAM 查询仍并行；命中接收间隔保持一拍，命中响应从 E+2 改为 E+3。所有 CPU 在途槽随 hold 保持；旧接口 `s2Hold/s2Kill` 对应最终 S3，`s1Kill` 清年轻 S1/S2，`s2Kill` 另清最终 CPU S3 及三段重查。内部 probe/refill/PTW 不受 CPU kill 撤销。原样保留请求、翻译、末地址、归属和快照失效；permissionEvent 在 S1/S2/S3 都须使旧 CPU 权限作废并重查；内部 PTW 在上下文变化时刷新。probe 开始条件覆盖 S1/S2/S3，阻塞的年轻 store 不得卡住完成旧 miss 所需的 probe。TLB miss 的 S3 旧主、S2/S1 年轻槽由各自 valid 保持，逐一重查，无额外请求复制。

同字 Store→Load 的 S1/S2/S3/PS 冲突共四拍，下一 Load 最早 E+5 接收。32B/64b 整行 refill 在 R+1…R+4 安装、R+5 回放 S0、R+8 回放 S3，lateReg 无冲突写回 R+9。新增快照级也必须处理同拍 probe/refill/tag/data 变更。普通 store 对齐与字节掩码在较早级生成，最后合法完成控制写许可；失败 store/SC 不写，AMO 值仍来自原 RMW 算法。

后端为 ID/EX/MEM/PERM/WB，PERM 随全局下游 hold 保持并随精确 kill 作废。E 为 EX，MEM=E+1、PERM=E+2、WB=E+3，W2=E+4。L1D S0/S1/S2/S3 对齐 EX/MEM/PERM/WB。CSR 在 PERM 求值、WB 生效；RAW/WAW、CSR 串行、interrupt 排空与 forwarding 必须包含 PERM。普通 ALU 旁路按 MEM>PERM>WB>W2>已捕获值；ALU 依赖与独立 load 命中仍 II=1。load-use 的依赖 ID 在 E+4 写穿透、EX 在 E+5。MUL 无冲突仍 E+4 写，常规 DIV 算法及迭代数不变；快 DIV 因 committed 门控在 E+4 写而非 E+3。lateReg、W2 的提交后 kill 行为不变。饥饿阈值仍 3，c+4 插入的 ID 气泡经新增 PERM 后于 c+9 让出 W2，冲突为 c+1…c+8，共 8。

FpUnit 保留两项请求边界，并新增两项无 flow/pipe 返回队列。实际原始 CVFPU out-fire 才捕获 tag/data/flags；其后最早一拍向 Writeback 提供结果。CVFPU ready 不组合依赖后端 ready；队列内 committed 所有权保持到实际物理完成，kill 不撤销已提交输出，busy/fflags-only 不提前清。队列输出在背压时稳定，即使原始跨单元仲裁的未接受输出可变化。FP32 ADDMUL 内部五拍保留，FP64 五→七拍，新增对齐控制→宽移位、归一化控制→宽移位边界。无停顿 backend 接收到结果最早 FP32 E+7、FP64 E+9（两端各一拍），II=1，独立算术/特殊值/舍入/flags 不变。

L1I 在已选定返回数据/地址/权限错误与 realigner 之间增加两项无 flow/pipe 返回队列，命中与 refill/error 响应都多一拍；accepted-undelivered 请求共最多两项并提前预留返回容量。背压不丢失/重复返回；flush 取消旧返回及信用，旧 miss 的迟到数据不得重新输出。BTB 仍全相联、容量/最低索引 winner/替换/查找拍数不变，按四项局部一热收束；walk-cache 仍按原 set/way/ASID/PLRU/全局规则，每 set 静态局部 PPN 选择，lookup miss 输出 0。PTW sLookup→内存请求拍数不变。

L2MemEngine 增加深度 l2Slots 的无 flow/pipe AR dispatch 队列。readReq.fire 同拍预留 order 与 dispatch 两项资源，order 包含尚未发 AR 的已接受读；AR 从寄存队头发出，最早多一拍，ARready 不再决定 order 写使能。内存仍按 AR 顺序返回完整行，RID/beat/RLAST 检查与错误归并不变。一项 WB 优先于同拍新读；已有 dispatch 队头在离开 AR 前阻止新 WB，避免 AR 在背压期间受新写影响。已有 WB 同行读等 B，不同行读可推进；R 完成才释放 order 容量。不得漏发/重复 AR 或复用在途 slot。
