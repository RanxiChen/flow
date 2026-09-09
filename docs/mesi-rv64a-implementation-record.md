# Flow 1/2/4 核 MESI + RV64A 实现记录

状态：**本地实现记录，代码已写入工作区，未编译、未提交。**

本文记录 feat/multicore-1-2-4 分支上这次"一步到位"重构实际做成了什么样：
配置入口、拓扑、各状态机的最终行为（含瞬态竞态规则）、RV64A 数据通路约定、
删除了哪些东西。权威需求仍是 `docs/multicore-1-2-4-implementation-spec.md`；
本文与其不一致处，以本文描述的**实际实现**为准去审查代码。

## 1. 用户拍板的决策

1. D$ 只说缓存一致性协议；legacy 直连 Wishbone 模式整体删除。
2. L2 是必选组件；**单核也过 L2**（single 就是 numHarts=1 的 cluster），
   不再单独维护单核顶层。`BreezeCoreWishbone`、`GenerateBreezeCoreWishbone`
   已删除，唯一 RTL 入口是 `GenerateBreezeMulticoreClusterWishbone <profile> [preset]`。
3. cache 相关旧测试删除重写为一套精简用例；core/frontend/mul/div 等与
   cache 无关的回归保留。
4. 范围：Chisel RTL + Chisel 测试 + LiteX python + runner + 多核固件全部一次做。
5. CPU↔D$ 保持脉冲协议（cache 完成脉冲指导 WB 级），不引入 ready。

## 2. 配置：单一入口

`design/src/main/scala/config/config.scala`：

- **`BreezeClusterConfig(profileName, numHarts, corePreset)` 是全设计唯一配置
  入口**。L1 几何从 `coreCfg.dcacheCfg` / `frontendCfg.cacheCfg` 派生（单一
  真源），L2 容量按冻结公式 `numHarts * 2 * L1D bytes` 派生，
  hartIdWidth/sharerWidth/txnIdWidth(=2) 按 hart 数派生。
- `L1CacheGeometry` 已删除（原先与 `DefaultDCacheConfig`、
  `BreezeCoreConfig.dcache*` 三重重复且不驱动硬件）。
- `CorePreset` 是 sealed（`Gshare`/`Baseline`），名字解析只在
  `CorePreset.fromName` 一处；所有入口默认 gshare，baseline 仅显式可选。
- `BreezeClusterPresets.single/dual/small`；8/16 核与未知名字抛异常。
- 遗留的 `top/config.scala`（FlowConfig/CoreParam 旧体系）已删除（无引用）。

## 3. 拓扑与顶层

`flow.top.BreezeMulticoreClusterWishbone(clusterCfg, enableTandem)`：

```text
per hart: BreezeCore（内含 L1I）+ BreezeDCache（私有、MESI、AMO/LR-SC）
共享:     BreezeL2Home（single-bank, blocking, directory, inclusive-for-D$）
          BreezeMmioArbiter + DCacheWishboneBridge → mmioWishbone master
          L2 → memoryWishbone master
输入:     resetAddr, msip[h], mtip[h], externalInterrupts[h]（仅 hart0 接入）
输出:     hartFatal[h], hartEStop[h], retire[h]（tandem）
```

- `BreezeHartTile` 已删除（纯连线包装，折进 cluster 顶层）。
- I$ 不参加 MESI：core 的 line refill 脉冲直接进 L2 的 `instrReq[h]`
  （GetInstr 语义，不入 sharer bitmap）。`ICacheWishboneBridge` 已删除。
- msip/mtip 由 LiteX 侧 CLINT 驱动；`core.io.machineSoftwareInterrupt` 为
  新增 core 输入，`core.io.reservationKill`（trap 脉冲）接 D$ 的 `resKill`。

## 4. L1D（`cache/BreezeDCache.scala`，整体重写）

几何冻结：8 KiB / 32 B line / 4 way / 64 set；tag=addr[31:11]，set=addr[10:5]。
MESI 编码在元数据上：I=!valid，S=valid，E=valid+excl，M=valid+dirty。
元数据（valid/excl/dirty/PLRU）为可复位寄存器组；tag/data 为同步读 SRAM。

FSM（21 态）：
`Idle → Lookup →`
- Load/LR hit：回数据（LR 设置 reservation）。
- Store/AMO hit M/E：本地 RMW（E→M 静默）→ `StoreHitWrite → Respond`。
  修复了旧实现 store hit 会把**整个 set** 的 excl 位清零的 bug（现在只清命中 way）。
- Store/SC/AMO hit S：`UpgradeReq/UpgradeWait`（GetM）。
- miss：victim 有效则 `PutReq/PutWait`（PutS/PutM，ack 后 victim way 立即置 I），
  再 `RefillReq/RefillWait`（Load/LR→GetS，Store/AMO→GetM）。
- uncached/device：`UncachedReq/UncachedWait` 走 `mmioReq/mmioRsp` 标量脉冲；
  **LR/SC/AMO 在该区域直接返回 access error，不上总线**。
- FENCE.I flush：`FlushScan/Read/WritebackReq/WritebackWait` 逐 set×way，
  S/E→PutS、M→PutM，每行等 ack；ack error → sticky `Fatal`。

### 4.1 三条瞬态竞态规则（本次补全的关键）

1. **等待态必须可服务 probe**：probe 任意状态锁存（1-entry），在 Idle 与所有
   等 Home 的状态（Put*/Upgrade*/Refill*/Flush*）优先服务后 resume；在
   Lookup/StoreHitWrite/Respond（本地 SRAM 变更与 AMO/SC RMW 窗口）内只锁存
   不服务——这就是有界的 atomicLock。若不允许等待态服务 probe，
   "L2 victim recall 指向请求者自己"会死锁。
2. **Stale Put 取消**：Put/Flush 写回在未被 Home 握手前若同行被 probe 拿走，
   重查元数据后**取消**该 Put（不是 resume），直接进入下一步。
3. **升级竞态**：Home 在处理 GetM 时按**自己目录**决定带不带数据——请求者
   不在 sharer bitmap 就必须带整行；L1 在 UpgradeWait 收到 hasData=1 就安装
   grant 数据，hasData=0 则断言本地 S 副本仍在。

### 4.2 Reservation / LR / SC

每 hart 一组：`resValid/resAddr/resSizeLog2/resLineAddr(line 粒度)`。

- LR：按 load 执行，成功返回时设置 reservation。
- SC：**永不 allocate**——reservation 只有在其行仍驻留时才可能有效
  （驱逐即清除），所以 miss/不匹配直接返回 1、零总线流量；hit M/E 写入即
  成功点，返回 0；hit S 先 GetM，等待期间 reservation 被 probe 清除则返回 1
  且把已授予的行装成 clean E（目录已把本 hart 记为 UNIQUE owner，L1 必须
  与之一致）。
- 清除事件：命中该行地址的任何 probe（不管 hit 与否）、该行被驱逐/flush、
  任何 store/SC/AMO 完成（保守）、SC 完成（无论成败）、`resKill`（trap）。

### 4.3 AMO

`cache/BreezeAmoAlu.scala`：组合逻辑，9 op × W/D；W 只算低 32 位（调用方
选好 32-bit half 放低位），MIN/MAX signed、MINU/MAXU unsigned。

数据约定（与 backend 冻结）：AMO 请求的 `wdata` 是**未移位的原始 rs2**，
D$ 自己按 addr[2] 定位 32-bit half 并生成写掩码；响应 `data` 是修改前的
**对齐 64-bit 旧字**，backend 按 load 同样规则提取+符号扩展写 rd。
原子性来源：先取得 M + RMW 窗口内不服务 probe（有界几拍）。

## 5. L2/Home（`cache/BreezeL2Home.scala`，修改）

- **MESI E 补齐**：D$ GetS 在 dir=NONE（hit 或 memory refill）授予 E 并记
  UNIQUE owner（旧实现只发 S/M，L1 的 E 逻辑全是死代码）。GetInstr 保持
  NONE、不入目录。
- **clean-E recall 合法化**：UNIQUE owner 对 ProbeToS/ProbeRecallInv 只有
  dirty(M) 才带数据；无数据响应时 L2 数组数据即最新（原实现会 assert）。
  victim 写回是否需要按 `victimDirty || 实际收到数据` 决定。
- **仲裁公平**：Idle 改为 2N 源（N 个 D$ coherence + N 个 I$ pending）
  round-robin（原为 PriorityEncoder 且 coherence 恒压 I$，可饿死）。
- GetM 授予始终带数据（满足 4.1 第 3 条的超集），唯一例外是
  UNIQUE owner==requester 的确认路径。
- HitUpdate 重写为单次 writeDir（原实现靠 last-connect 双写）；
  PutAck 的 grant 寄存器在 Idle 接受请求时清零，不再残留上笔事务的值。

目录不变量与断言保持：单 owner、pendingAck 未清不 GrantM、dirty victim
未写回不覆盖、txnId/lineAddr 匹配等。

## 6. Core 侧 RV64A / 软件中断（decode/backend/CSR）

接口（`interface/interface.scala`，冻结）：`BackendMemReq` 新增
`memOp{Load,Store,Lr,Sc,Amo}`、`amoFunc{Swap,Add,Xor,And,Or,Min,Max,MinU,MaxU}`、
`aq`、`rl`。响应：LR/AMO 走 load 提取路径（AMO 的 data 是旧值对齐字）；
SC 的 data 直接是 0/1 写 rd。

- decode：opcode 0x2f，funct3 010/011，funct5 精确映射（LR 要求 rs2=0），
  其余组合 illegal instruction，不退化为普通 load/store；aq=inst[26]、rl=inst[25]。
- backend：发请求前做自然对齐检查（LR→cause 4，SC/AMO→cause 6，mtval=地址，
  且不发请求）；rsp.error：LR→cause 5、SC/AMO→cause 7；原子请求发出到响应
  期间冻结流水级（复用阻塞访存机制），加"不得二次发请求"断言；aq/rl 由
  严格顺序+单 outstanding 天然满足，保留显式注释与断言。
- CSR：misa.A=1；mip.MSIP(bit3) 只读反映输入，mie.MSIE(bit3) 可写，
  software interrupt cause=3，优先级 MEI > MSI > MTI；mhartid 每 Tile 参数化
  （此前已做对）。
- trap 时 core 输出 `reservationKill` 清 D$ reservation。

## 7. CLINT / LiteX / runner / 固件（python 侧）

- `litex_wrapper/flow/clint.py`（新增）：`BreezeClint(numHarts)`，经典布局
  msip[h]=0x0200_0000+4h（32-bit，bit0）、mtimecmp[h]=0x0200_4000+8h、
  mtime=0x0200_bff8；64-bit Wishbone slave 用 `sel` 区分同 word 的两个 msip
  （写 msip[1] 不得破坏 msip[0]）；mtimecmp 复位全 1；不存在的 hart 槽位
  RAZ/WI，绝不别名到现有 hart。`machine_timer.py`（单 hart 版）已删除。
- `cluster.py`：`msip`/`mtip` 改为 `Signal(numHarts)` 并逐 hart 接到
  `i_io_msip_<h>`/`i_io_mtip_<h>`（原先 msip 恒 0、mtip 单根广播）。
  RTL 加载与 cluster-profile.txt 异源校验保持。
- `core.py`：不再有独立单核 RTL 路径——`Flow` 现在是 `FlowCluster` 的
  single-profile 子类，`set_core_preset` 转发到 `set_cluster_config("single",…)`，
  历史 `flow` CPU 名和 runner 调用保持兼容。
- `breeze_sim.py` / `multicore_sim.py`：实例化 `BreezeClint`（region 与
  mtime/mtimecmp 偏移不变），接 `cpu.msip`/`cpu.mtip`，新增
  `BREEZE_MSIP` 常量；`run_mcu.py --elaborate` 改为生成 single cluster。
- `run_multicore.py`：watchdog 为 deadline + reader 线程驱动（修审计 F-10：
  原实现对"进程挂死但 stdout 不关"完全失效），超时 kill 进程组、退出码 124；
  `--test` 路径运行前读 RTL 目录 cluster-profile.txt 做异源校验（修 F-11
  自拼自验）；新测试已进 TEST_REGISTRY。
- 固件（沿用 `software/breeze-mcu` 的 crt/链接脚本/barrier 基建，
  `MARCH` 默认升到 `rv64ima_zicsr_zifencei`）：
  - `apps/multicore_atomic.c`（`BREEZE_ATOMIC_CASE` 1–4）：
    ① 9 个 AMO op × W/D 定向向量（含 signed/unsigned MIN/MAX 边界、
    .W 旧值符号扩展、addr[2] 半字选择与邻半字不受损、模加回绕）；
    ② 多 hart AMOADD.D/.W 争用，总和精确无丢失更新；
    ③ LR/SC 往返 + 裸 SC 必须失败且不写 + LR/SC 自旋锁保护非原子临界区；
    ④ hart0 持 reservation 期间 hart1 写**同 32 B 行的另一个字**，SC 必须
    失败且不写内存，随后重试必须成功。LR…SC 窗口写成单个 asm 块，
    避免编译器插入 spill store 误清 reservation。
  - `apps/multicore_ipi.c`（`BREEZE_IPI_CASE` 1–3）：
    ① IPI：逐个 target 写 msip，handler 校验 cause=0x8000…0003、清自己
    msip、其余 hart 计数必须不变（这就是 msip[0]/[1] 不别名的证据）；
    ② remote FENCE.I：hart0 改写 SRAM 中可执行 stub（`li a0,1`→`li a0,2`），
    target 先调用一次把旧编码拉进 I$，收 IPI 后在 handler 里 `fence.i`，
    再调用必须得到新值（缺 fence.i 或缺 L2 从 D$ owner 回收脏行都会失败）；
    ③ per-hart timer：阶段 A 只给最高 hart 设 mtimecmp，其余 hart 已开
    MTIE 但必须一个中断都收不到（广播 mtip 会在此失败）；阶段 B 每个 hart
    设自己的 mtimecmp 并只能收到自己的。
  - single 档跑自测形态（self-IPI、自身 timer、无争用 LR/SC、AMO 定向）。
  - 注册的测试名：`amo-directed`、`amo-contention`、`lrsc-success`、
    `lrsc-fail`、`ipi`、`remote-fencei`、`per-hart-timer`。

## 8. 测试（Chisel）

- 删除/重写：`BreezeDCacheSetAssocSpec`（按新接口）、`BreezeDCacheCoherentSpec`
  （扩充：E 安装/静默升级、升级竞态、Put 取消、probe 数据规则、LR/SC/AMO、
  flush、resKill）、`BreezeL2HomeSpec`/`SmallSpec`（适配 E 授予、clean-E
  recall、round-robin 公平性）、`BreezeCoreConfigSpec`（新配置 API）；
  新增 `BreezeAmoAluSpec`（18 组合定向向量）。
- 保留：`breezecacheSpec`（I$ 未改）、core/frontend/backend/mul/div 回归
  （sim 支持层只补了新 core 输入的初始化）。

## 9. 已删除清单

```text
design/src/main/scala/top/BreezeHartTile.scala            纯转发包装
design/src/main/scala/top/BreezeCoreWishbone.scala        单核顶层（single=cluster）
design/src/main/scala/top/GenerateBreezeCoreWishbone.scala
design/src/main/scala/top/config.scala                    遗留配置体系（无引用）
design/src/main/scala/bus/ICacheWishboneBridge.scala      单核 I$ 桥（I$ 走 L2）
litex_wrapper/flow/machine_timer.py                       单 hart timer（→ clint.py）
config.scala 中的 L1CacheGeometry                          三重几何之一
BreezeDCache 的 coherent 双模式与全部 legacy 状态/分支
core/RegFile.scala 中失效的 import flow.top._
```

## 10. 已知边界 / 未做

- 外部中断仍只送 hart0（无 PLIC，冻结边界）。
- 普通 FENCE 保持 NOP（无隐藏队列/多 outstanding 的前提仍成立）。
- 全局仍是单一致性事务、blocking L1/L2；不做 MSHR/hit-under-miss。
- 未编译未仿真：所有"应当如此"的行为需 `sbt test` + Verilator 矩阵验证。
