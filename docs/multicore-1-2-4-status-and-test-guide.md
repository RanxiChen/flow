# 多核 1/2/4 核 —— 当前进度与测试/仿真运行手册

状态：**本地工作记录，不进入 Git 提交**（与 `docs/multicore-1-2-4-environment-workflow.md` 同一约定）
日期：2026-08-20
分支：`feat/multicore-1-2-4`

---

## 一、本轮改了什么

只动了一个设计文件：`design/src/main/scala/cache/BreezeL2Home.scala`。

### 1. 问题根因（复核结论）

外部报告说"meta 是寄存器数组，所以 SV 爆了"，方向对，但**没说到点子上**。

真正的代价不是"用寄存器存"，而是 `meta(setIndex)` 这种**动态下标**：全模块约 31 个访问点，每个点都会展开成一个 `sets:1` 的选择器。`small` 档 256 组 × 31 个访问点 → 生成的 SystemVerilog 里全是巨型 mux 树。同时报告里"256 × 41 位"的位宽也是错的 —— 41 是 Vec 的元素个数，不是位宽；实际单路条目宽度是 `1+1+2+sharerWidth+hartIdWidth`，`small` 档合并 tag 后是 83 bit，`single` 档 51 bit。

### 2. 实际做法

把"tag 数组 + 目录 meta 寄存器数组"合并成**每路一块 SRAM，一个 word 同时装 tag 和该路目录状态**：

```scala
class L2WayDir  { valid; dirtyToMemory; dirState(2); sharers; ownerId }
class L2DirEntry{ dir: L2WayDir; tag: UInt(tagWidth) }

val dirArray  = Seq.fill(ways)(Module(new flowSRAM(sets, dirEntryWidth, "l2dir")))
val roundRobin= RegInit(VecInit(Seq.fill(sets)(0.U(...))))   // 替换指针仍留在触发器
```

按路拆分是关键：写第 w 路的 word 不会碰到其它路，**不需要 read-modify-write**。（我在第二轮回答里说"绕不开 RMW"，那是错的，按路拆分之后就没有这个问题。）

### 3. 必须承认的一处修正

我在第三轮说"**状态机一个状态都不用动**" —— **这句话是错的**。实现时撞上两件事，各自逼出一处状态机改动：

| 触发原因 | 被迫增加的机制 |
|---|---|
| `SyncReadMem` 没有复位语义，valid 位上电是未定义的 | 新增 `Init` 状态 + `initSet` 走位器，复位后花 `sets` 个周期把每组每路清零，期间 `coherenceReq.ready` 拉低 |
| `flowSRAM` 是**单端口**，写的那一拍 `data_out` 被强制置 0；而 `Compare` 拍同时要读目录、又要写目录 | 目录写**延迟一拍**（`dirWrPending` 寄存器组），否则读回的零会反灌进产生这次写的比较逻辑，形成组合环 |

所以准确说法是：**主干状态迁移图没变，但多了一个复位初始化状态和一条延迟写通路。**

### 4. 顺带修掉的问题

- `SendPutAck` 里 `grantState` 没赋值，会把上一次事务的残留值发给 L1 → 现在显式 `:= BreezeGrantState.S`。
- 两处 W001（`BreezeDirectoryState` 非法编码）→ 改用 `BreezeDirectoryState.safe(...)`，警告清零。
- 新增 4 条断言：
  - `PopCount(wayHit) <= 1` —— 不允许多路同时命中同一 tag
  - 命中行 / victim 行的 dirState 必须是合法编码
  - `SHARED` 状态的 sharer 位图不能为空
  - 包含性回收时 probe 目标集合不能为空

### 5. 实测收益

| 档位 | 改前 SV 行数 | 改后 | 缩小 |
|---|---|---|---|
| single | 66,638 | 2,027 | 32× |
| dual | 127,782 | 2,676 | 47× |
| small | 244,120 | 3,933 | 62× |

- `small` 档文件体积：11 MB → 148 KB
- `small` 档目录触发器：21,248 (256×83) → 768（只剩 roundRobin 256×3）
- W001 出现次数：2 → 0；`always` 块数量不变（仍 4）
- `BreezeL2HomeSpec` 全套：约 25 分钟（单个用例）→ **2 分 04 秒（12 个用例全跑完）**

---

## 二、算完全实现了吗？还差什么

### 已完成且已验证

- [x] 目录/tag 合并进 SRAM，动态下标消除
- [x] 复位走位器（`Init` 状态）
- [x] 延迟一拍目录写，无组合环
- [x] W001 清零、4 条新断言、`SendPutAck` grantState 修复
- [x] 三档 cluster RTL（single / dual / small）全部能重新生成
- [x] `flow.cache.*` 整包回归：**58 个用例全过，0 失败**
  - `BreezeL2HomeSpec` 12/12（2 分 04 秒）
  - `BreezeL2HomeSmallSpec` 8/8（1 分 59 秒）
  - 另含 `BreezeDCacheCoherentSpec`、`BreezeDCacheSetAssocSpec`、`BreezeAmoAluSpec`、`BreezePLRUSpec`、`BreezeCacheSpec`

### 尚未验证（这是当前最大的空洞）

1. **`flow.cache` 以外的全量回归没有结果。** 后台跑的那次 `sbt test` 被中断，没留下任何日志，`/tmp/fulltest.log` 不存在。也就是说 backend / frontend / core / sim / divider / multiplier 六个包**这次一次都没跑过**。理论上这次改动只碰 L2，不该波及它们，但这是推断，不是实测。
2. **LiteX 端到端仿真一次都没跑。** `run_multicore.py` 里 22 个注册用例，本轮全部没执行。
3. **未提交。** 工作区脏，`git diff --stat`：
   ```
   design/src/main/scala/cache/BreezeL2Home.scala | 256 ++++++++++-------
   sim/litex/run_multicore.py                     |  12 ++
   ```
   其中 `run_multicore.py` 的 12 行是本轮之前就存在的改动，不是这次加的。

### 已知但**没有**修的遗留缺陷

- **Wishbone 侧缺看门狗**：总线若不返回 ack，L2 会永久停在 `MemRead` / `VictimWrite`，没有超时逃逸。这一项从头到尾都不在本轮范围内。
- **`Test / parallelExecution := false` 没加**。外部报告建议加，我没加：它不在批准的改动清单里，而且会拖慢所有人的测试。SV 缩小 62 倍之后，原本要防的 Verilator OOM 基本消失了。真要加就是在 `design/build.sbt` 里加这一行。

---

## 三、测试清单与运行方式

所有 sbt 命令都在 **`design/` 目录**下执行。

### 3.1 最常用的四条

```bash
cd design

sbt build                                        # 只编译（主源码 + 测试源码），不跑测试
sbt test                                         # 全量单元测试
sbt "testOnly flow.cache.BreezeL2HomeSpec"       # 只测一个模块
sbt 'testOnly flow.cache.BreezeL2HomeSpec -- -z "GetS miss"'   # 只测一个用例（-z 是子串匹配）
```

### 3.2 单元测试全清单（按包）

| 包 | 测试类 | 覆盖对象 |
|---|---|---|
| `flow.cache` | `BreezeL2HomeSpec` | L2/Home 单核+双核 MESI 主干（12 例） |
| | `BreezeL2HomeSmallSpec` | 4 核场景 S1–S7（8 例） |
| | `BreezeDCacheCoherentSpec` | L1 D$ 一致性侧 |
| | `BreezeDCacheSetAssocSpec` | L1 D$ 组相联 |
| | `BreezeAmoAluSpec` | 原子指令 ALU |
| | `BreezePLRUSpec` / `BreezeCacheSpec` | 替换算法 / I$ |
| `flow.backend` | `BreezeBackendDivSpec` `BreezeBackendMulSpec` `BreezeBackendGShareSpec` | 后端 |
| `flow.frontend` | `BreezeBTBSpec` `BreezePHTSpec` `BreezeFrontendSpec` `BreezeFrontendFE001Spec` `BreezeFrontendFE002Spec` `BreezeFrontendGShareSpec` `MiniDecodeSpec` | 前端/分支预测 |
| `flow.core` | `BreezeCoreSpec` `BreezeCoreCustomInstrSpec` `BreezeCoreNoFASESpec` `BreezeCoreNoFASECustomInstrSpec` `RegFileSpec` `CSRFileSpec` `MulDecodeSpec` | 整核 / 寄存器堆 / CSR |
| `flow.divider` | `RiscvDivUnitSpec` `UnsignedRadix4DividerSpec` | 除法器 |
| `flow.multiplier` | `RiscvMulUnitSpec` `SignedMul65x65Spec` | 乘法器 |
| `flow.config` | `BreezeCoreConfigSpec` | 配置推导（含 numHarts ∈ {1,2,4} 约束） |
| `flow.sim` | `BreezeCoreSimAppSpec` `BreezeCoreSimMemoryLoaderSpec` `BreezeCoreGShareSpec` | 仿真外壳/取指加载 |

按包批量跑：

```bash
sbt "testOnly flow.cache.*"       # 本轮实测：58 passed, 0 failed
sbt "testOnly flow.frontend.*"
```

L2 单个用例名（`-z` 用的子串）可直接从源码里抄：
`design/src/test/scala/cache/BreezeL2HomeSpec.scala`、`BreezeL2HomeSmallSpec.scala`，
例如 `-z "S2"`、`-z "round-robin"`、`-z "dirty transfer"`。

### 3.3 生成 RTL

```bash
cd design

# 单核 SoC 顶层（build.sbt 的官方别名）
sbt elaborate                       # == runMain flow.top.GenerateBreezeCoreWishbone

# 多核 cluster（本轮验证用的就是这个）
sbt 'runMain flow.top.GenerateBreezeMulticoreClusterWishbone single'
sbt 'runMain flow.top.GenerateBreezeMulticoreClusterWishbone dual'
sbt 'runMain flow.top.GenerateBreezeMulticoreClusterWishbone small gshare'   # 第二参数 gshare|baseline
```

输出在 `build/rtl/cluster/<profile>/<preset>/`，含 `filelist.f` 和 `cluster-profile.txt`。
快速自检生成规模：

```bash
wc -l build/rtl/cluster/small/gshare/*.sv
grep -c W001 build/rtl/cluster/small/gshare/*.sv     # 应为 0
```

### 3.4 LiteX 多核仿真

主入口 `sim/litex/run_multicore.py`：

```bash
cd /home/chen/leisure/flow

python3 sim/litex/run_multicore.py --profile small --test small-sharing
python3 sim/litex/run_multicore.py --profile dual  --test upgrade --trace
python3 sim/litex/run_multicore.py --profile single --elaborate --test boot
```

常用参数：`--profile {single,dual,small}`（必填）、`--test <名字>`、`--core-preset {gshare,baseline}`、`--elaborate`（先重新生成 RTL）、`--trace`（出波形）、`--timeout`（默认 600 s）、`--output-dir`。

档位定义：single = 1 hart / 16 KB L2，dual = 2 hart / 32 KB，small = 4 hart / 64 KB。

**注册用例（22 个）**，括号里是它支持的档位：

| 类别 | 用例 |
|---|---|
| 基础 | `boot`(全档) `generic`(single) `l2-eviction`(single) |
| 双核一致性 | `sharing` `upgrade` `dirty-read` `dirty-transfer` `same-line` `same-line-race`（均 dual） |
| 四核一致性 | `small-sharing` `small-upgrade` `small-dirty-transfer` `small-same-line` `small-same-line-race` `small-l2-eviction`（均 small） |
| 原子/LR-SC | `amo-directed`(全档) `amo-contention`(dual,small) `lrsc-success`(全档) `lrsc-fail`(dual,small) |
| 平台 | `ipi` `remote-fencei` `per-hart-timer`（全档） |

其它 LiteX 脚本：

| 脚本 | 用途 |
|---|---|
| `sim/litex/run_mcu.py` | 单核 MCU 固件跑到结束 |
| `sim/litex/run_cluster_mcu.py` | 在某个 cluster 档位上跑 MCU 风格应用 |
| `sim/litex/run_gshare_regression.py` | 同一固件跑 baseline 和 gshare 两遍做对比 |
| `sim/litex/breeze_sim.py` / `multicore_sim.py` | LiteX target 定义（一般由上面的脚本调用，不直接跑） |
| `sim/litex/test_run_multicore.py` | run_multicore.py 自身的 pytest：`python3 -m pytest sim/litex/test_run_multicore.py` |

### 3.5 遗留 Verilator 仿真（Makefile 系）

`sim/{top,common,datapath,mem,simple}/` 下各有 Makefile，属于早期手写仿真台，不在多核回归路径上：

```bash
make -C sim/top verilog     # 生成 verilog
make -C sim/top build       # verilator 编译
make -C sim/top run         # 运行
make -C sim/top clean
```

`sim/top/Makefile` 还有 `hello` `perf` `sram_sim` `sim_tinymem` 等零散目标。

---

## 四、如果要继续，建议的顺序

1. `cd design && sbt test` 跑一次真正的全量回归，把第二节那个空洞补上（这是目前唯一有实质风险的未知项）。
2. 至少跑三个 LiteX 用例确认端到端没坏：`--profile single --test boot`、`--profile dual --test dirty-transfer`、`--profile small --test small-l2-eviction`。
3. 上面两步都绿了再提交。按 `docs/multicore-1-2-4-environment-workflow.md` 的规矩：`docs/` 下的本地稿不进 Git；同步到 Alan 验证机只走 GitHub，不用 scp；未验证的大改动不往 `main` 上堆。
