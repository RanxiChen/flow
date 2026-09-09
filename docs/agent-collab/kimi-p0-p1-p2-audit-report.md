# Kimi 第一阶段：P0/P1 独立审计报告（只读）

状态：**本地审计报告，untracked，不进入 Git 提交**

执行会话：Kimi K3，reasoning effort = max（用户指定；会话内无法自检 effort 标志，如实声明）

任务书：`docs/kimi-k3-p0-p1-audit-and-repair-plan.md` 第 5 节

本阶段只读：未修改、未暂存、未提交任何源码；未编译；未下载依赖；未登录 Alan。

---

## 0. 审计锚点与方法

审计时实测锚点（命令见任务书 3 节，已逐条重跑）：

```text
branch:  feat/multicore-1-2-4
HEAD:    82a4c3cc95cc4523e15ab1e86878fa00e2c15dcb
origin/feat/multicore-1-2-4: same
BreezeL2Home.scala sha256: 3064b9618eb121e7d16f69052ee0dd7dfe5f8c11400f01e032938771ce8f4404
```

与任务书锚点完全一致。未跟踪文件除任务书列出的已知资产外，仅多 `docs/kimi-k3-p0-p1-audit-and-repair-plan.md`（任务书自身，预期内）和本报告。

已完整阅读：`docs/multicore-1-2-4-implementation-spec.md`（1299 行）、`docs/multicore-1-2-4-environment-workflow.md`（563 行）、任务书全部、四个 P0/P1 commit 的 diff、相关源码与测试。

诚实边界声明：本机无 SBT/Chisel 环境，本阶段所有"能否编译/仿真"判断均来自控制流与 Chisel 语义的静态阅读；旧 Alan PASS 证据未被本审计复验，新鲜验证属于第二/七节工作。本报告不把任何旧日志当作当前 SHA 的证据。

---

## 1. Git 与范围审计（任务书 5.1）

实测命令输出摘要：

- `git diff --check`：干净（exit 0）。
- `git diff --name-status origin/main...HEAD`：19 个文件，全部在 P0/P1 预期范围内（config、Coherence、cluster top/generator、DCache、两个测试、SimSupport、core.py、两个 Makefile、breeze_sim.py、run_mcu.py、run_multicore.py、三个 frontend spec）。无 `docs/`，无生成物，无日志。
- commit 边界：`7d3de88`=P0（配置骨架），`a8c35cc`+`3c8c97d`+`82a4c3c`=P1（DCache 替换 + 测试修复 + RAW 修复）。边界清晰，可独立审核。
- 未发现把 P2/P3 内容伪装进 P0/P1：`BreezeL2Home.scala` 保持未跟踪；DCache 无 probe/coherence 通道；无 Tile。
- `a8c35cc` 中混入的 `config.scala` 改动（+38）经核对为 `DefaultDCacheConfig` 重写，属 P1 范围，可接受。

结论：Git 状态与范围 **无异常**。

---

## 2. P0 配置与骨架审计（任务书 5.2）

逐项核对结果（证据为当前工作区文件）：

- 无参数默认统一为 GShare：**一致**。`BreezeCoreConfig.useGShare=true`（config.scala:112）、`BreezeFrontendConfig` 默认 `GShareBranchPredictorConfig()`（config.scala:56）、`BackendConfig.branchPredKind=GShare`（config.scala:67）、`BreezeCoreWishbone` 默认 `gshare()`（BreezeCoreWishbone.scala:19）、`GenerateBreezeCoreWishbone` 无参默认 gshare 且走 `fromPreset`（GenerateBreezeCoreWishbone.scala:21-22）、`core.py` `core_preset="gshare"`（core.py:33）、`breeze_sim.py:589/725`、`run_mcu.py:74`、`run_multicore.py:176`、`BreezeCoreSimSupport.scala:247/266`、两个 Makefile `CORE_PRESET ?= gshare`。`BreezeCoreSimApp` 要求显式 preset 参数（BreezeCoreSimSupport.scala:451），无隐式默认。`misa_read_test` 无 Makefile，不是活跃 launcher。
- baseline 只能显式选择，输出目录隔离：`fromPreset` 仅接受 baseline/gshare（config.scala:170-178）；单核 RTL 输出 `build/rtl/<preset>/`，cluster 输出 `build/rtl/cluster/<profile>/<preset>/`（GenerateBreezeMulticoreClusterWishbone.scala:28）；`core.py` 校验 `core-preset.txt` marker（core.py:180-191）。
- single/dual/small 严格 1/2/4 Hart：config.scala:289-291。
- 8/16 Hart 与 standard/max 明确失败：`BreezeClusterConfig` require numHarts∈{1,2,4}（config.scala:254-256）、`fromName` 抛异常（config.scala:294-303）、generator `require`（GenerateBreezeMulticoreClusterWishbone.scala:19-25，runMain 下非零退出）。
- L1I/L1D 8 KiB/4-way/32 B/64 sets：`L1CacheGeometry` 默认及 require（config.scala:185-207）；`l1i == l1d` require（config.scala:257）。现有 I$ 几何未被改动（DefaultICacheConfig 保持 64 sets/4 ways/32 B）。
- L2 = numHarts×2×L1D、8-way、single-bank：require 冻结（config.scala:261-264、220）；preset 推导（config.scala:286）。
- hartIdWidth/sharerWidth：config.scala:269-270（max(1,ceil(log2 N)) / N），正确。
- coherence Bundle：字段与 spec §8 逐条一致（Coherence.scala:44-115），方向自洽（Req/ProbeResp 由 L1D 侧驱动 valid，Grant/Probe 由 Home 侧驱动 valid），txnId 保留，lineAddr 按 plen 参数化，backpressure 语义注释明确。**Bundle 定义本身无缺陷**（被误用的是 L2 草稿，见第 5 节）。
- cluster P0 顶层为诚实 tie-off skeleton：BreezeMulticoreClusterWishbone.scala:51-73 全部输出 tie-off，注释明确标注 "P0 skeleton"；generator 只打印 elaboration 信息；runner 空 registry 报 "(none yet)"（run_multicore.py:235-240），elaborate-only 路径打印 `[MULTICORE-ELABORATE-OK]` 而非 PASS（run_multicore.py:229-232）。无虚假功能声明。
- 旧单核入口兼容：仅默认值变化，接口/字段未动。
- `cluster-profile.txt` 字段满足 spec §6.2（GenerateBreezeMulticoreClusterWishbone.scala:50-61）。

### 发现

```text
ID: F-01
Severity: Low
Scope: tests
Evidence: design/src/test/scala/config/BreezeCoreConfigSpec.scala:31-52 覆盖了原始 case class
  默认、公开 preset、fromPreset 三层；spec 3.4 要求"原始 case class 默认、公开 preset、
  生成器默认和 Python CLI 默认四层一致"。Python CLI 层（sim/litex/breeze_sim.py:725、
  sim/litex/run_mcu.py:74、sim/litex/run_multicore.py:176、litex_wrapper/flow/core.py:33）
  无任何自动化断言；本次确系人工核对一致。
Expected: 四层默认值有一致性回归保护。
Actual: Python CLI 默认层无测试覆盖；任一层被改回 baseline 不会被测试抓到。
Impact: 低。当前一致；未来回归无保护。
Repair proposal: 在 runner 自测试文件（F-12 新增）中对各 Python 入口源码做
  default="gshare" 静态断言，或对 cluster generator 默认做等价检查；不改被测 Python 文件。
Regression needed: 自测试中包含"Python CLI 默认=gshare"用例。
Repair authorized in Phase 2: yes
```

```text
ID: F-02
Severity: Low
Scope: environment
Evidence: sim/litex/README.md:73 仍写 "All commands above default to --core-preset baseline"；
  sim/breezecore/tests/branch_test/README.md:68 仍写 "可以通过 CORE_PRESET=gshare … 切到
  gshare 配置"。两文件为 tracked 文件（非 docs/），P0 未同步更新。
Expected:  tracked 文档与 P0 后的默认行为一致。
Actual: 文档仍描述旧默认。
Impact: 低。误导读者，不影响功能与验证。
Repair proposal: 更新两段描述。注意：两文件不在任务书 6.1 默认白名单内，
  需用户/Codex 批准扩大白名单，否则保持现状仅记录。
Regression needed: 无（文档）。
Repair authorized in Phase 2: no（超出默认白名单，待用户决定）
```

P0 其余各项无发现。

---

## 3. `run_multicore.py` 与验证 oracle 审计（任务书 5.3）

对控制流逐条推演（sim/litex/run_multicore.py 全文 267 行已读）：

### 发现

```text
ID: F-10
Severity: Critical
Scope: runner
Evidence: sim/litex/run_multicore.py:62-82（run_streaming）。
  控制流：Popen 后第 78-80 行 `for line in process.stdout:` 对管道做阻塞式逐行读，
  没有任何超时；第 81 行 `process.wait(timeout=timeout)` 只在管道 EOF（即子进程
  已经关闭 stdout，通常等于已经退出）之后才执行。timeout 参数对"进程挂死但 stdout
  保持打开"这一 watchdog 真正要覆盖的场景是死代码。此外 `subprocess.TimeoutExpired`
  未被捕获，wait 抛超时后没有 process.kill()/communicate() 回收：traceback 退出码虽为
  1（非零，偶然满足），但子进程被遗留在 Alan 上继续运行。
  任务书 5.3 点名的两种挂死形态均不可检测：
  (a) stdout 很久不换行/部分输出后挂死——for 循环永久阻塞在 readline；
  (b) 进程挂死但管道不关闭——同上。
Expected: watchdog 以 wall-clock 覆盖子进程整个生命周期；超时杀死并回收子进程；
  runner 以可预期的方式非零退出。
Actual: 超时只在子进程已结束后才被检查；超时路径不杀进程；常见挂死形态下 runner
  自身永久挂起，--timeout 形同虚设。
Impact: 多核仿真一旦 deadlock，Alan 上的 runner 不会失败退出而是无限挂起（任务书
  明令禁止"延长 timeout 掩盖 deadlock"，而当前实现连"发现 deadlock"都做不到），
  并可能积累遗留 Verilator 进程。
Repair proposal: 重写 run_streaming：以 deadline 轮询（select/reader 线程+Queue 均可），
  到期 kill 进程组、drain 输出、返回专用超时码（如 124）并保留已捕获输出供
  FAIL/fatal/marker 检查；所有异常路径收敛为受控非零退出而非裸 traceback。
Regression needed: F-12 自测试中的 timeout 用例：一个 sleep 型假子进程，断言 runner
  在约 2×timeout 内非零退出且无残留子进程。
Repair authorized in Phase 2: yes
```

```text
ID: F-11
Severity: High
Scope: runner
Evidence: sim/litex/run_multicore.py:191-193。`build_cluster_marker`（99-105 行）用
  runner 自己的 PROFILES 常量拼出 BREEZE_CLUSTER marker，`print` 后立刻把同一字符串
  交给 `validate_marker`（108-143）与同一组 PROFILES 常量比对。该校验按构造不可能失败，
  profile marker 不来自 elaborated hardware 或任何生成产物，形成"runner 自拼自验"闭环。
  部分缓解：--elaborate 路径（206-226 行）会读 Scala 生成器写出的 cluster-profile.txt
  并与 Python 常量交叉比对，这是真正的双源校验；但 --test 路径（未来真正跑仿真时）
  完全不读任何生成产物。
Expected: profile marker 来自 elaborated hardware/生成产物，runner 只做异源比对。
Actual: 启动 marker 的"打印并校验"是同源自证；只有 --elaborate 分支存在真实校验。
Impact: 若 PROFILES 常量与 Scala preset 发生漂移（例如 L2 公式改了一侧），非
  --elaborate 的测试运行不会发现；spec 22 的 marker 合同名存实亡。
Repair proposal: --test 路径启动时强制读取并比对 cluster-profile.txt（缺失即失败），
  和/或要求仿真输出中独立出现 BREEZE_CLUSTER marker 并与生成产物比对；
  build/validate 自拼字符串仅保留为格式检查。
Regression needed: F-12 自测试中的 profile mismatch 用例：篡改 cluster-profile.txt
  一个字段，断言 runner 非零退出。
Repair authorized in Phase 2: yes
```

```text
ID: F-12
Severity: Medium
Scope: runner / tests
Evidence: `ls sim/litex/`：README.md、breeze_sim.py、run_gshare_regression.py、
  run_mcu.py、run_multicore.py——不存在任何 runner 自测试文件。
Expected: 任务书 5.3 要求自测试覆盖 success、nonzero、timeout、missing marker、FAIL、
  fatal、profile mismatch，且负向用例必须证明 shell exit code 非 0。
Actual: 无任何自测试；watchdog/oracle 的正确性目前只靠人工读代码。
Impact: F-10/F-11 这类控制流缺陷可以长期存在而不被发现；第二阶段任何 runner 修复
  也无回归抓手。
Repair proposal: 新增 sim/litex/test_run_multicore.py（不依赖真实 RTL，用假子进程
  脚本注入各形态输出），覆盖任务书列出的全部七种形态并断言退出码。
Regression needed: 即本文件自身；Alan 阶段单独运行并记录命令/测试数/exit code。
Repair authorized in Phase 2: yes
```

```text
ID: F-13
Severity: Low
Scope: runner
Evidence: run_multicore.py:79 `print(line, end="")` 无 flush（输出滞后，仅观感）；
  196-197 行 output_dir 只按 profile/preset 区分，未按 test 区分（spec 22 表述为
  "每个 profile/test 使用独立 output directory"）；RuntimeError/CalledProcessError
  以裸 traceback 退出（退出码为 1，可接受但噪声大）。
Expected: 见上。
Actual: 见上。
Impact: 低。当前 registry 为空，无实际测试可互相覆盖产物。
Repair proposal: 转发子进程输出时 flush；评估 output_dir 是否纳入 test 维度
  （需权衡 Verilator binary 复用）；把可预期错误收敛为受控 SystemExit 信息。
Regression needed: 随 F-12 自测试覆盖。
Repair authorized in Phase 2: yes（随 F-10/F-11 一并）
```

runner 其余核对点（无发现）：子进程非零→自身非零（245-246）；FAIL marker 先于 PASS
检查（248-252）；PASS marker 必须出现在子进程输出中（254-256）；fatal/assertion 全文
扫描（258-259，`\b` 边界不会误中 `dcacheFatalError`）；空 registry 如实报告；git short
SHA 打印（194）；timeout≤0 拒绝（188-189）。

---

## 4. P1 DCache 实现审计（任务书 5.4）

阅读范围：`BreezeDCache.scala`（459 行）、`BreezeDCacheSetAssocSpec.scala`（365 行）、
`flowSRAM`（mem/sram_1r1w.scala）、`BreezePLRU`（BreezeCache.scala:12-80）、接口定义
（interface.scala:56-77, 420-462）、`PMAChecker`、`config/breeze_mcu_platform.json`、
`origin/main` 旧 DCache 全文 diff、`82a4c3c` 修复 diff。

### 4.1 RTL 侧核对结论（无发现项）

- 几何/索引与 `DefaultDCacheConfig` 一致：8 KiB/4-way/32 B/64 sets；tag=addr[31:11]
  21 bit。32-bit 物理 tag 安全：`PMAChecker` 对 ≥2^32 地址先 deny（PMAChecker.scala:61-74），
  Lookup 中 `!allowed` 分支先于 tag 使用（BreezeDCache.scala:247-249）。
- SRAM 时序：Idle 拍发读（164-171，地址取**当前请求**的 set），Lookup 拍消费
  tagRdata/dataRdata，符合 flowSRAM 同步读（sram_1r1w.scala:30 `mem.read(addr, re)`）。
- 82a4c3c 修复核实：flowSRAM 在 `we=1` 的拍把 `data_out` 组合置 0（sram_1r1w.scala:23-29），
  旧代码在 Lookup 拍边读 `dataRdata(hitWay)` 边写同 array，必然读到 0 行、丢未写字节——
  commit message 与硬件语义吻合。修复后 Lookup 拍（we=0，读数有效）算 merge 并锁存，
  StoreHitWrite 拍独占写（286-295）；`mergeStore` 在整行 256 bit 上以
  `addr(4,3)` 选 64-bit lane、8-bit wmask 做字节合并（134-147），**结构上**覆盖全部
  byte mask/offset（测试覆盖缺口见 F-22）。全文件再无同拍读写同一 array 的路径
  （RefillWait 安装拍 re=0；FlushRead 消费拍 we=0）。
- 命中 one-hot：hit=valid&&tag，重复 tag 不可达（miss 才安装），结构成立。
- invalid-first + tree-PLRU：`BreezePLRU.replace_way_select`（BreezeCache.scala:26-79）
  与 DCache 内 `touchWay`（60-66）位约定一致（b0=plru(2) 根、b1=plru(1) 左、b2=plru(0) 右，
  位指向久未用侧）；PLRU 只在成功安装（RefillWait 375 行）或命中 touch 时更新，
  refill 失败不写 meta——符合 spec 7.2。
- dirty victim 成功写回前绝不覆盖：WritebackWait 错误分支不动 meta（326-335）；
  RefillWait 错误分支不动 tag/data/meta（348-353）；victim 保留且可再命中。
- flush：FlushScan→FlushRead 两拍符合同步 SRAM；0..255 覆盖 64×4；写回错误进
  sticky Fatal（426-448）；flush 后再访问必然 miss（meta 已清）。
- uncached/MMIO：走旁路单拍请求，地址/mask/数据约定与 origin/main 旧实现逐行一致
  （diff 核对 UncachedReq/UncachedWait 块）。
- PMA-denied 不触达下级：Lookup 直接 Respond+error（247-249）。
- HPM：access 在 Idle（每请求一次）、miss/uncached 在 Lookup（每请求一拍，该状态
  必在一拍内离开），一请求一票。
- 一个 CPU pulse 一次 response：rsp.valid 仅 Respond 一拍（198, 383-385）； busy 期
  重复 pulse 有 assertion 兜底（219-226）。

### 4.2 RTL 侧发现

```text
ID: F-20
Severity: Medium
Scope: P1
Evidence: BreezeDCache.scala:150-152（`reqAddr(10,5)`、`reqAddr(31,11)` 硬编码切片）、
  121（`flushIndex` 硬编码 8.W）、163/393-394（flush set/way 切片）、406/440（255.U）。
  而 DefaultDCacheConfig（config.scala:77-105）的 require 允许其它合法 2 次幂组合
  （如 capacityBytes=4096→32 sets）。RTL 已有 `require(ways == 4)`（46 行），但没有
  对 sets/lineBytes 的等价 require；非默认但合法的 cfg 会被错误接受并静默错切地址。
Expected: 硬编码 bit slice 必须有 elaboration-time require 与 cfg 绑定（任务书 5.4 原话）。
Actual: 仅 ways==4 受保护；sets==64/lineBytes==32 无保护。
Impact: 潜伏。当前唯一实例化点（BreezeCoreWishbone.scala:56）使用默认几何，不触发；
  任何未来非默认配置会得到错误硬件或难以定位的 elaboration 失败。
Repair proposal: 最小修复——在 46 行旁增加
  `require(cfg.sets == 64 && cfg.lineBytes == 32, ...)`（或把切片改为按 cfg 推导，
  但那是更大 diff，第一阶段不建议）。
Regression needed: 测试中断言非默认几何构造 BreezeDCache 抛 IllegalArgumentException。
Repair authorized in Phase 2: yes
```

### 4.3 测试 oracle 发现

```text
ID: F-21
Severity: High
Scope: tests
Evidence: BreezeDCacheSetAssocSpec.scala:128-142（PLRU 替换测试）、144-155（set 隔离
  测试）、117-126（"no writeback" 填充测试）。内存模型初值 byte(addr)=addr&0xff
  （13-15 行），全部访问为干净 load：被换出的 line 重新访问时，无论"命中（未换出，
  错误）"还是"miss+refill（正确）"，返回数据完全相同；测试只比较最终数据和 error，
  全程不统计 nextLevelReq 的次数/地址。"no writeback" 标题断言同样无任何写回计数。
  对照任务书 5.4 弱 oracle 清单，命中两条："注释说发生 miss/eviction 但没有观察下级
  请求"、"数据初始模式导致换错 victim 仍得到相同值"。
Expected: T2 要求"第五个同 set 地址按 PLRU 替换正确 victim"、"不同 set 不互相驱逐"，
  必须通过统计下级 request/writeback 证明 hit/miss/victim。
Actual: 选错 victim、不驱逐、跨 set 驱逐三类 bug 全部无法被这些测试抓到。
Impact: P1 最核心的替换策略正确性实际上没有回归保护；T2 关键项名实不符。
Repair proposal: harness 记录每笔事务的 nextLevelReq 脉冲序列；用"命中=零新请求、
  miss=恰好一次 refill、写回=恰好一次 line write"重写上述三测试的断言；配合脏数据
  变体使 victim 身份可从写回地址区分。
Regression needed: 强化后的测试在"故意选错 victim"的变异下必须失败（变异核对以
  控制流评审形式记录，无法在本地执行）。
Repair authorized in Phase 2: yes
```

```text
ID: F-22
Severity: Medium
Scope: tests
Evidence: 全部测试地址 offset=0（sramAddr 默认 offset 0，36-37 行及全部调用点）；
  store 只覆盖 wmask=0xff 与 0x0f 且字节偏移为 0（161、167 行）；sizeLog2 恒为 3。
  lineWord 的 `addr(4,3)` 选 lane 路径（BreezeDCache.scala:124-132）、mergeStore 的
  移位路径（134-147）、uncached scalarMask 的 `reqAddr(2,0)` 移位路径（185-195）
  从未被激励。任务书点名"StoreHitWrite 修复是否覆盖所有 byte mask/offset，而不只
  覆盖低 4 字节"——当前只覆盖低 4 字节。
Expected: 1/2/4/8-byte size、非零 byte offset、line 内非零 word lane 的 load/store
  组合有定向覆盖。
Actual: 仅 8-byte、零偏移、两种 mask。
Impact: 中。StoreHitWrite 的 merge 逻辑在 addr(4,3)≠0 路径上无回归；uncached
  子拍 mask 无回归。
Repair proposal: 增加 offset∈{8,16,24} 的 load 校验、mask∈{0x03<<2, 0x0f<<4, 0xff
  高 lane} 的 store-merge 校验、非对齐 beat 内偏移的 uncached load/store 并断言
  下级收到的 addr/mask/data。
Regression needed: 新用例随 F-21 的 harness 计数一并落地。
Repair authorized in Phase 2: yes
```

```text
ID: F-23
Severity: Medium
Scope: tests
Evidence（每条均指向任务书 5.4 清单项）：
  (a) refill-error 测试（193-249）在收到 error 后即结束，未再访问 victim——"refill
      error 后 victim 的 valid/dirty/data 保留并可再次访问"未验证；
  (b) writeback error 路径（BreezeDCache.scala:328-331）完全无测试；
  (c) flush 写回错误进 sticky fatal（426-448）无测试；
  (d) flush 测试（261-311）两条脏 line 在 set0/set1（flushIndex 低区），若 flush 只扫
      前一小段（例如沿用旧 8-entry 假设）照样 PASS；"走完全部 256 line"未被钉死；
      flush 后旧 line 失效、后续访问必须重新 miss 也未验证；
  (e) PMA-denied 访问不触达下级接口：无测试（ denied 地址应见 error 且零下级脉冲）；
  (f) MMIO 只有一拍对齐 8-byte load（251-259）；uncached store、mask、地址与 error
      返回均未核对；
  (g) isWriteAck 任何测试都未断言（runReq 返回但被 `_` 丢弃）；一个 pulse 只产生一次
      response 未验证（harness 拿到首个 rsp 即返回）；
  (h) hpm.dcacheUncached 计数无测试。
Expected: 上述行为各有能抓住具体错误的定向用例。
Actual: 全部缺失。
Impact: 中。错误处理与边界语义无回归保护；其中 (a)(b)(d) 直接对应 spec 7.3 保留语义。
Repair proposal: 扩展 harness 支持按阶段注入 error（refill/writeback/flush-writeback），
  补 (a)-(h) 定向用例；flush 用例把脏 line 放到 set63/way3 并在 flush 后重访。
Regression needed: 同上。
Repair authorized in Phase 2: yes
```

```text
ID: F-24
Severity: High
Scope: tests
Evidence: BreezeDCacheSetAssocSpec.scala:89-100：harness 看到 nextLevelReq.req 即
  `pending = Some(...)`，若上一笔尚未服务则被静默覆盖；全程没有"同时只允许一笔
  outstanding"的协议断言。任务书弱 oracle 清单原话："memory model 自动接受重复脉冲，
  掩盖 RTL 重复请求"。
Expected: RTL 重复发请求（协议违背）必须使测试失败。
Actual: 重复脉冲被 harness 吸收，测试继续。
Impact: 高（测试基础设施层面）。DCache 任何多发/重发 bug 对所有用例不可见。
Repair proposal: req 脉冲到达且 pending 未清空时直接 fail；并统计每笔 CPU 事务的
  下级请求序列供 F-21 断言复用。
Regression needed: 随 F-21 一并验证（变异评审）。
Repair authorized in Phase 2: yes
```

---

## 5. P2 L2/Home WIP 只读审计（任务书 5.5）

仅阅读，未修改/暂存/提交 `design/src/main/scala/cache/BreezeL2Home.scala`
（451 行，sha256 与任务书锚点一致）。对照任务书 5.5 清单逐项：

```text
ID: W-01
Severity: Critical
Scope: P2-WIP
Evidence: BreezeL2Home.scala:136 `io.coherenceGrant(h).ready := true.B`——
  BreezeCoherenceGrantIO 中 ready 是 Input（Coherence.scala:72），而 66 行该 Vec
  未 Flipped，对 L2Home 而言这是模块 Input，模块内部驱动 Input 在 Chisel elaboration
  必然报错。同类第二处：144-146 与 432-434 行驱动 `io.instrResp(h).vld/data/error`，
  而 L1CacheMissRespIO 三个字段全是 Input（interface.scala:433-437），70 行同样未
  Flipped（应为 Flipped）。
Expected: Bundle Input/Output 方向由正确模块驱动。
Actual: 两处方向性错误。
Impact: 该文件当前无法通过 Chisel elaboration（Scala 语法本身无碍，错误发生在
  Chisel 绑定检查阶段）。与"未提交草稿"状态自洽；按任务书本阶段不在 Alan 编译它。
Repair proposal: 属 P2 工作：grant 通道删除对 ready 的自驱动并真实消费它；
  instrResp 声明改 Flipped。
Regression needed: P2 阶段的 elaboration + BreezeL2HomeSpec。
Repair authorized in Phase 2: no（任务书禁止本轮修改该文件）
```

```text
ID: W-02
Severity: Critical
Scope: P2-WIP
Evidence: BreezeL2Home.scala:396-405：MemReadWait 在最后一拍先
  `readBeats(memBeat) := io.memoryWishbone.dat_r`（寄存器写），同拍又以
  `readBeats.asUInt`（399 行）拼 installedLine——读到的是 readBeats(3) 的旧值，
  最后一个 beat 的数据永远不进入安装行。任务书 5.5 原话点名的缺陷形态。
Expected: 最后一个读 beat 真正进入安装 line。
Actual: 每次 L2 refill 安装的 line 高 64 bit 为旧值（首次为 0），且同一错误数据经
  grantDataReg（418 行）返回给请求者。
Impact: 任何经 L2 的取指/读数据在高 8 字节上必然损坏。草稿不可用于 P2。
Repair proposal: 末拍用 `Cat(dat_r, readBeats(beats-2,0).asUInt)` 之类的组合拼接
  （或末拍先进寄存器、次拍安装）。
Regression needed: P2 定向测试：refill 后逐 beat 比对完整 32 B。
Repair authorized in Phase 2: no
```

```text
ID: W-03
Severity: Critical
Scope: P2-WIP
Evidence: BreezeL2Home.scala:283-285：Compare miss 分支先
  `victimDirStateReg := meta(setIndex).dirState(victimWay)`（284），下一行立即
  `when(meta(setIndex).valid(victimWay) && victimDirStateReg =/= NONE)`（285）——
  读到的是该寄存器的**上一事务旧值**。旧值为 NONE 而新 victim 实为 UNIQUE 且
  dirtyToMemory=false 时，直接走 MemReadReq：owner 的脏副本被静默丢弃（既不 recall
  也不 invalidate），L1 与 L2 各持一份独立脏数据——典型一致性数据丢失。同一形态
  第二处：340-343 行 `victimDirtyReg := true.B` 后同拍 `when(victimDirtyReg || …)`，
  I$ recall（UNIQUE 命中，249-255 行进入）被错误导入 MemReadReq，用内存旧数覆盖
  刚收回的数据并把错数据经安装路径发给 I$（409-418）。
Expected: victim metadata 不使用同拍写入寄存器的旧值（任务书原话）。
Actual: 两处同拍写后读。
Impact: 单 Hart P2 场景即可触发（D$ 持有 M 的 line 被取指命中/被替换）。
Repair proposal: P2 重写 FSM 时用当拍组合值做分支决策。
Regression needed: P2 测试：UNIQUE-clean 目录下的 victim 替换与 I$ recall。
Repair authorized in Phase 2: no
```

```text
ID: W-04
Severity: Critical
Scope: P2-WIP
Evidence: (a) BreezeL2Home.scala:355 与 365：VictimWriteReq/Wait 的 Wishbone 地址
  恒为 `(victimLineAddr >> 3)`，四个 beat 写同一字地址——对比正确的 MemReadWait
  394 行 `(lineAddrBase >> 3) + memBeat`。(b) memBeat 只在 MemReadReq（386）和写回
  末拍（370）清零；refill 完成后停留在 3（398-420 的末拍分支不清零），下一笔带脏
  victim 的事务进入 VictimWriteReq 时 memBeat=3，只写一个 beat 即离开。
Expected: 每个 64-bit beat 地址递增；事务寄存器每个新事务正确初始化（任务书原话）。
Actual: 写回地址不递增；写回起点 beat 取决于上一事务残留。
Impact: victim 写回只落 1/4 行或落在错误地址——内存数据损坏。
Repair proposal: P2 重写 beat 计数与地址生成（Req 拍即锁存/清零 memBeat，地址随
  beat 递增）。
Regression needed: P2 测试：refill 紧跟 dirty-victim 写回，逐 beat 核对地址与数据。
Repair authorized in Phase 2: no
```

```text
ID: W-05
Severity: High
Scope: P2-WIP
Evidence: (a) 127 行 `io.coherenceReq(h).ready := state === Idle` 对所有 Hart 广播，
  而 Idle 仲裁（199-209）只锁存最小编号 Hart——多 Hart 同拍 valid 时未被选中的
  Hart 同样看到 ready，请求被吞（任务书点名项）。(b) InstallGrant（430-444）一拍
  后无条件回 Idle，不观察 grant.ready（且 ready 被 136 行自驱），L1D 若反压则
  grant 丢失。(c) VictimProbeReq（314-322）只拉高一拍 probe.valid，从不检查
  probe.ready，payload 在 backpressure 下不保持——probe 丢失则 VictimProbeWait
  死等。(d) instrReq 脉冲只在 Idle 被锁存（210-219、230-238），而 L1CacheMissReqIO
  契约（interface.scala:416-418）明确要求下级"在 req 有效的周期无条件锁存"——
  L2 忙时 I$ refill 脉冲丢失，I$ 永久等待。
Expected: ready/valid 真正 handshake 后才推进状态；backpressure 下 payload 稳定；
  只对实际接受的请求给 ready。
Actual: 四类通道均存在。
Impact: 多 Hart 必错；单 Hart 下 I$/D$ 并发 miss 即可死锁（P2 集成即触发 (d)）。
Repair proposal: P2 重写握手：grant/probe 状态驻留至 fire；req ready 只授予被仲裁
  选中的 Hart；instrReq 增加挂起锁存。
Regression needed: P2 测试含 backpressure 注入与 I$/D$ 并发 miss。
Repair authorized in Phase 2: no
```

```text
ID: W-06
Severity: High
Scope: P2-WIP
Evidence（对照 spec 11 节）：GetM 命中（265-268）不对 SHARED 其余 sharer 发任何
  probe、不 recall 不同 owner，直接清 sharers 立 requester 为 UNIQUE owner；
  GetS 命中 UNIQUE（269-275）保持 UNIQUE、把 requester 加进 sharers 并直接授予 S，
  不发 ProbeToS、返回的可能是 L2 旧数据；PutUpdate（295-312）对 PutS 一律
  dir=NONE 并清空**全部** sharer（spec 11.3 要求只清 src、最后一个离开才 NONE）；
  Put 未命中时落入 miss/refill 路径（278-292），把协议错误静默变成一次内存读并
  安装 SHARED 垃圾目录（410-417）；probe response 不校验 srcHart/txnId（328-334）；
  全文件没有任何协议 assertion（spec 11.3"source/state 不匹配必须 assertion"）。
Expected: NONE/SHARED/UNIQUE 目录不变量与 spec 11 节逐条对应。
Actual: 多 Hart 语义全面不满足；单 Hart 下被"只有一个请求者"偶然掩盖。
Impact: 按当前草稿直接做 P3 必然产生双 owner、脏数据丢失等一致性破坏。
Repair proposal: P2 阶段先只实现并验证单 Hart 子集（此时 SHARED 只有自 sharer、
  UNIQUE 只有自 owner，语义可收敛正确），多 Hart 分支待 P3 重写在通过验证的
  骨架上。
Regression needed: P2 单 Hart 目录不变量 assertion + 定向测试。
Repair authorized in Phase 2: no
```

```text
ID: W-07
Severity: High
Scope: P2-WIP
Evidence: 320 行 victim 为 UNIQUE 时发 ProbeToS（spec 11.4 要求 recall+invalidate，
  即 ProbeRecallInv）——owner 在被换出的 line 上残留有效副本；316 行 SHARED victim
  的 probe 目标硬编码 `0.U`，多 Hart 下只失效 Hart0；无 pendingAckBitmap
  （spec 10.2 事务上下文凭据之一），无法表达"等全部 Ack 才 GrantM"。
Expected: probe opcode 与最终目录状态一致；inclusive 换出覆盖全部 sharer。
Actual: 见上。
Impact: 多 Hart 一致性破坏；单 Hart 下 ProbeToS 与 ProbeRecallInv 的区分影响
  L1D 侧终态正确性。
Repair proposal: 随 W-05/W-06 的 FSM 重写一并纠正。
Regression needed: P3 测试：换出后旧 owner/sharer 必须 ack 失效。
Repair authorized in Phase 2: no
```

```text
ID: W-08
Severity: Medium
Scope: P2-WIP
Evidence: 375-377 行写回 err 进 sticky Fatal 但不给请求者任何返回——请求者挂死
  （spec 10.3 的 FatalOrErrorReturn 只完成一半）；424-427 行 refill err 发生在 victim
  probe/写回之后时，目录仍登记已被失效的 sharer（错误路径不变量漂移）；
  VictimWriteReq/MemReadReq（351-359、380-388）不采样 ack，零等待 slave 下首拍
  事务被重放一次（幂等 RAM 无害，属粗糙实现）。
Expected: 错误返回路径完整；目录不变量在错误路径上保持。
Actual: 见上。
Impact: 中。错误场景挂死或目录残留。
Repair proposal: 随 FSM 重写补全错误返回与目录回滚/保持。
Regression needed: P2 测试：mem 写回 err、refill err 后的目录与请求者返回。
Repair authorized in Phase 2: no
```

```text
ID: W-09
Severity: Medium
Scope: P2-WIP
Evidence: 全文按 numHarts 参数化编写（Vec 接口、仲裁、sharer bitmap、ownerId），
  即 P3 多 Hart 逻辑已混入草稿，但所有多 Hart 路径（W-05a、W-06、W-07）均未验证
  且经静态阅读确认有误；P2 只需要单 Hart 子集。
Expected: P2 单 Hart 能力与 P3 多 Hart 逻辑不混成未验证复杂度（任务书原话）。
Actual: 混在一起。
Impact: 中。增加 P2 验证负担，且给"P2 已完成"造成误判空间。
Repair proposal: P2 实现时以单 Hart 收敛正确为目标组织 FSM，多 Hart 泛化在 P3
  单独引入并验证。
Regression needed: 见 W-06。
Repair authorized in Phase 2: no
```

### P2 WIP 处置建议：**PARTIAL-REWRITE**

逐条说明：

- **保留**（接口/存储框架）：`L2SetMeta` 元结构（27-34，字段与 spec 10.1 对应）、
  几何推导与 require（49-62）、通道 Vec 骨架（修正方向后）、与 spec 10.3 大致对应的
  状态名骨架、Wishbone master 的基本接线形态。这部分约占文件三分之一且与 spec 对齐。
- **必须重写**（关键 FSM 与数据通路）：握手/仲裁（W-05）、Compare/PutUpdate 目录规则
  （W-06）、victim recall/probe 流程与 opcode（W-03、W-07）、beat 计数与 Wishbone 地址
  生成（W-02、W-04）、错误返回路径（W-08）、每事务寄存器初始化，并补协议 assertion
  与 pendingAckBitmap。
- **不选 REUSE**：W-01~W-04 任意一条都使草稿无法 elaborate 或在单 Hart 下即产生
  错误数据，"小修"不足以覆盖。
- **不选 REWRITE**：元结构、几何与通道骨架可直接复用且有 spec 依据，整体推倒会
  丢掉这部分已对齐的工作。

---

## 6. 第一阶段结论

- **P0 verdict：AUDIT PASS**。四个默认层（Scala case class、preset、generator、
  Python CLI）人工核对一致；skeleton 声明诚实；commit 边界干净。遗留两条 Low
  （F-01 测试层缺口、F-02 过期 README），不构成功能性否定。注意：本 verdict 仅表示
  实现/测试/声明与任务门槛自洽，Alan 新鲜证据属第二/七节。
- **P1 verdict：FAILED（测试 oracle 维度）**。RTL 实现本身在默认几何下未发现功能性
  缺陷（含 82a4c3c 修复的语义核实），仅有 F-20（Medium，参数保护缺失）；但测试套件
  存在 F-21（High）、F-24（High）、F-22/F-23（Medium）——T2 的替换/隔离关键断言
  当前无法失败，harness 会吸收重复请求。在 oracle 修复并经 Alan 复验前，P1 不能宣称
  AUDIT PASS。
- **runner/evidence verdict：FAILED**。F-10（Critical，watchdog 对挂死无效且超时不
  回收子进程）、F-11（High，profile marker 同源自证）、F-12（Medium，无自测试）。
  当前空 registry 下 runner 不会产生假 PASS，但其控制流不满足任务书 5.3 的控制流
  证明要求。
- **P2 WIP 建议：PARTIAL-REWRITE**（理由见第 5 节末）。
- 本轮整体结论：按任务书 1 节口径，当前只能给出 **P0/P1 FAILED（可修复，修复项
  已列明白名单与回归要求）**；不宣称 P2 已实现、不宣称 single cluster 已接真实 L2、
  不引用旧日志充当证据。

### 建议修复文件白名单（提交用户/Codex 批准）

```text
sim/litex/run_multicore.py                    # F-10, F-11, F-13
sim/litex/test_run_multicore.py               # 新增，F-12（对应任务书 <runner self-test file>）
design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala   # F-21, F-22, F-23, F-24
design/src/main/scala/cache/BreezeDCache.scala               # F-20（仅两行 require）
```

明确不修改：`BreezeL2Home.scala`（任务书禁止）、`docs/**`（禁止）、
`Coherence.scala` / `config.scala` / cluster top 与 generator（无缺陷）、
`sim/litex/README.md` 与 `branch_test/README.md`（F-02，超出默认白名单，待批准）。

### 未修改任何文件的证明

本阶段全程只读。`git status --short` 输出见文末附录（执行于报告写就后）：除任务书
列出的已知未跟踪资产与本报告自身外，无任何 tracked 文件改动。

### 停止声明

按任务书 5.6，本阶段到此停止，等待用户/Codex 审核。不自动进入第二阶段，不开始 P2。

---

## 附录：git status --short（报告完成后实测）

```text
?? design/src/main/scala/cache/BreezeL2Home.scala
?? docs/agent-collab/kimi-p0-p1-p2-audit-report.md
?? docs/dcache-set-assoc-design.md
?? docs/diagrams/mesi-l1-l2-state-machine-layout-v1.json
?? docs/diagrams/mesi-l1-l2-state-machine-standards.json
?? docs/diagrams/mesi-l1-l2-state-machine.md
?? docs/kimi-k3-p0-p1-audit-and-repair-plan.md
?? docs/multicore-1-2-4-environment-workflow.md
?? docs/multicore-1-2-4-implementation-spec.md
?? docs/multicore-mesi-rv64a-implementation-design.md
```

补充：`git diff --stat`（working tree）为空、`git diff --cached --name-only`（暂存区）
为空——本阶段未修改、未暂存任何 tracked 文件。列表中 `BreezeL2Home.scala` 与全部
`docs/**` 均为任务书认定的本地未跟踪资产（本报告为新增 untracked 文件，按任务书
5.6 保持 untracked）。
