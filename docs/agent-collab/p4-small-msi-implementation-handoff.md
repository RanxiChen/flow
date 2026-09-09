# Flow P4 四核 MSI 实现与验证交接书

状态：**交给下一位实现 agent 的单阶段执行合同；本文件只保存在本地，不进入 Git 提交**

编写时间：2026-08-18（Asia/Shanghai）

目标分支：`feat/multicore-1-2-4`

本阶段唯一目标：在不引入 MESI E、RV64A、CLINT/IPI 或其他后续功能的前提下，把当前已经验证的 2-hart MSI 路径收敛为可验证的 4-hart `small` profile。

---

## 0. 给执行 agent 的第一条命令

先完整阅读本文件，再按以下顺序阅读：

1. `docs/multicore-1-2-4-implementation-spec.md`
2. `docs/multicore-1-2-4-environment-workflow.md`
3. `design/src/main/scala/cache/Coherence.scala`
4. `design/src/main/scala/cache/BreezeL2Home.scala`
5. `design/src/main/scala/cache/BreezeDCache.scala`
6. `design/src/test/scala/cache/BreezeL2HomeSpec.scala`
7. `design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala`
8. `sim/litex/run_multicore.py`
9. `sim/litex/run_cluster_mcu.py`
10. `software/breeze-mcu/apps/multicore_boot.c`
11. `software/breeze-mcu/apps/multicore_t4.c`
12. `software/breeze-mcu/runtime/start.S`

若本文件与总 specification 冲突，以总 specification 为准；若旧设计稿或 `agent.md` 与上述文件冲突，以本文件和总 specification 为准。

读完后先输出以下内容，等待自检完成再改代码：

- 当前本地 branch、HEAD、`origin/feat/multicore-1-2-4`；
- tracked diff 与 untracked 文件清单；
- 本阶段拟修改文件白名单；
- 你对第 4～7 节协议和状态机的复述；
- 你准备先写的测试名称及每项 oracle。

不需要向用户索要普通代码细节决策。接口、状态转移和验收条件已经在本文冻结；实现细节由 agent 自己决定，但不得改变外部协议或降低 oracle。

---

## 1. 当前真实状态锚点

### 1.1 Git 状态

本文件编写时的本地只读快照：

```text
checkout: /home/chen/leisure/flow
branch:   feat/multicore-1-2-4
HEAD:     fbcc0e89e84d7f066eba9be88f3a5269528d0603
origin/feat/multicore-1-2-4: same
merge-base with main: 7b9420c65bab2207d10f4c008dfd38fc3fdad039
tracked diff: none
```

当前已知 untracked 文档如下，全部属于用户/本地材料，不得删除、覆盖或加入提交：

```text
docs/agent-collab/kimi-p0-p1-p2-audit-report.md
docs/dcache-set-assoc-design.md
docs/diagrams/mesi-l1-l2-state-machine-layout-v1.json
docs/diagrams/mesi-l1-l2-state-machine-standards.json
docs/diagrams/mesi-l1-l2-state-machine.md
docs/kimi-k3-p0-p1-audit-and-repair-plan.md
docs/multicore-1-2-4-environment-workflow.md
docs/multicore-1-2-4-implementation-spec.md
docs/multicore-mesi-rv64a-implementation-design.md
```

本文件创建后还会多出：

```text
docs/agent-collab/p4-small-msi-implementation-handoff.md
```

执行时必须重新核对 SHA 和工作区；不得把本快照当作永远不变的常量。若 HEAD 已前进，先审查新增提交是否仍属于 P3/P4，再决定是否继续。若出现未知 tracked diff，立即停止，不清理。

### 1.2 当前阶段结论

按提交和 Alan 日志区分：

| 阶段 | 当前结论 | 边界 |
|---|---|---|
| P0 配置/骨架 | 已提交并经过后续全量回归 | 不能用早期旧日志替代当前 SHA |
| P1 8 KiB、4-way L1D | 已提交并经过后续全量回归 | legacy 与 coherent 两种下级路径仍必须兼容 |
| P2 single L2/Home | 已提交并有 single cluster 回归 | L2 为 blocking、single-bank、8-way |
| P3 dual MSI | **功能证据 PASS，证据包装不完整** | 当前只证明 2 hart MSI，不证明 4 hart/MESI/A |
| P4 small MSI | **尚未实现闭环** | 配置和 RTL 已参数化，但 runner、firmware 和四核专用 oracle 未闭环 |
| P5+ | 未开始 | 本阶段禁止进入 |

### 1.3 P3 Alan 证据

2026-08-18 只读复核时，Alan 状态为：

```text
hostname: chen-System-Product-Name
user:     chen
arch:     x86_64
checkout: /home/chen/FUN/flow
branch:   feat/multicore-1-2-4
HEAD:     fbcc0e89e84d7f066eba9be88f3a5269528d0603
status:   clean
```

证据目录：

```text
/home/chen/FUN/flow-runs/20260817T-p3-dual-fbcc0e8/
```

其中可核对到：

- `sbt-test.log`：129/129 tests passed；
- `dcache-coherent-test.log`：4/4 passed；
- dual：boot、sharing、upgrade、dirty-read、dirty-transfer、same-line、same-line-race 均有 runner verified PASS；
- single cluster：boot、generic、l2-eviction 均有 runner verified PASS；
- legacy single：generic、timer、uart 均有 firmware PASS；
- dual boot mask：expected=`0x3`、actual=`0x3`、fail=`0x0`。

但是该 P3 目录没有按执行手册保存统一的 `meta.txt`、`command.txt`、`exit_code.txt` 和 `summary.txt`。因此：

- 可以把 P3 当作 P4 的代码基线；
- 不允许宣称“最终证据链已经完整”；
- P4 最终回归必须在最终目标 SHA 上创建规范化证据目录，并同时覆盖 P3 回归。

---

## 2. 本阶段范围与停止条件

### 2.1 必须完成

1. `small` 严格为 4 hart，`hartIdWidth=2`，L2=64 KiB、8-way、32 B line、single-bank。
2. L2/Home 的 request、probe、probe response、grant 路径在 4 hart 下无请求丢失、重复响应或 bitmap 截断。
3. 四个 hart 可启动，`mhartid=0..3` 唯一，四个 32 KiB stack slice 不重叠，boot mask=`0xf`。
4. 四个 L1D 对同一行可形成 `SHARED sharers=0b1111`。
5. 任一 hart 从四共享者中申请 M 时，只探测另外三个 hart，并且三个 InvAck 全部收到后才可 GrantM。
6. dirty owner 可以是 hart 2 或 hart 3；ownership transfer 必须传递最新整行数据。
7. inclusive L2 eviction 必须使所有相关 L1 副本失效；UNIQUE victim 必须先回收 owner 数据，必要时写回内存，然后才能覆盖。
8. single、dual 全部既有回归保持 PASS。
9. runner、firmware 和配置 marker 能独立证明运行的确是 `small/4/65536/gshare`，不能由 runner 自打印后自证。
10. 在 Alan 保存最终 SHA 对应的完整证据包。

### 2.2 明确禁止

本阶段不得实现或顺手修改：

- MESI E grant 或 E→M silent upgrade；
- RV64A、LR/SC、AMO、`aq/rl`；
- CLINT、MSIP/IPI、remote FENCE.I；
- S/U mode、Sv39、Linux、PLIC；
- 8/16 hart；
- banked L2、private L2/shared L3；
- MSHR、hit-under-miss、多 outstanding coherence transaction；
- cache 几何、line size 或 replacement policy 重新设计；
- GShare 参数调优或性能优化；
- main merge、rebase 或已推送历史改写。

### 2.3 完成后必须停止

P4 全部门槛满足后，提交最终报告并停止。不得自动进入 P5。若发现 P5 才能解决的问题，记录为后续项，不得用 E 状态绕过 P4 MSI bug。

---

## 3. 机器、Git 与提交纪律

### 3.1 唯一允许的职责划分

本地 `/home/chen/leisure/flow`：

- 阅读、编辑源码和测试；
- `rg`、`git diff`、`git diff --check` 等静态审查；
- 逐路径白名单 staging；
- 创建 commit；
- push 到 `origin/feat/multicore-1-2-4`；
- 阅读 Alan 日志并修复。

Alan `/home/chen/FUN/flow`：

- 只 fetch/switch/pull 已 push commit；
- 只在这里运行 Python 自测试、SBT compile/build/test、firmware cross-compile、Chisel elaboration、LiteX/Verilator 仿真；
- 证据写到 checkout 外 `/home/chen/FUN/flow-runs/...`；
- 不编辑源码、不 commit、不 push。

GitHub 是两台机器之间唯一的源码同步通道。禁止 `scp`/`rsync` 未提交源码到 Alan。

### 3.2 本地也禁止执行的命令类别

为了严格满足“只能在 Alan 编译、仿真”，本地不得运行：

- `sbt compile/build/test/elaborate/runMain`；
- `make` firmware、RISC-V GCC/objdump；
- LiteX、Verilator、仿真 runner；
- 任何会生成 RTL、ELF、BIN、obj_dir、build/target 的命令。

本地只做不产生构建结果的静态检查。

### 3.3 staging 规则

严禁：

```bash
git add .
git add -A
git add --all
git add docs
git reset --hard
git clean -fd
```

只允许逐路径 `git add <file>`。每个 commit 前必须执行：

```bash
git diff --cached --name-only
git diff --cached --check
git status --short --branch
```

并确认：

```bash
if git diff --cached --name-only | rg -q '^docs/'; then
    echo 'FAILED: docs must stay local'
    exit 1
fi

if git diff --cached --name-only | rg -q '(^|/)(build|target|obj_dir)/|\.(vcd|log|elf|bin)$'; then
    echo 'FAILED: generated artifact staged'
    exit 1
fi
```

### 3.4 推荐提交结构

允许按真实工作拆成 2～3 个本地 commit：

1. `test(cache): define four-hart MSI P4 oracles`
2. `cache: close four-hart directory MSI`（仅当测试证明 RTL 确实需要修改；不得为了有 RTL commit 而硬改）
3. `sim: add small-profile MSI firmware regressions`

测试 commit 可先于实现 commit，但无需在 Alan 保留一个故意失败的 checkout。可以在本地形成连续提交，push 后只验证最终候选 SHA。每次失败修复必须新增 commit；不得 amend 已经在 Alan 验证过的 SHA。

---

## 4. 冻结的一致性接口协议

所有 coherence 地址都是 **32 B 对齐的 byte address**。`txnId` 为 2 bit；当前 Home 全局只允许一个 coherence transaction in flight。

### 4.1 L1D → Home request

| 字段 | 含义 |
|---|---|
| `valid/ready` | 标准 ready/valid；`valid && !ready` 时全部 payload 必须稳定 |
| `opcode` | `GetS / GetM / PutS / PutM / GetInstr`，其中 D$ 不发送 `GetInstr` |
| `srcHart` | 4-hart 时为 2 bit，必须等于物理端口号 0..3 |
| `txnId` | grant 和相关 probe/probeResp 必须原样关联当前事务 |
| `lineAddr` | 32 B 对齐；Home 必须再次对齐后锁存 |
| `hasData/lineData` | `PutM` 必须带最新 256-bit line；`PutS/GetS/GetM` 不带数据 |

Home 在 `Idle` 只对实际选中的一个 hart 拉高 `ready`。未选中的 hart 必须继续保持 `valid/payload`，不得把同时出现的请求吞掉。当前固定低 hart 优先可保留；P4 不要求重写为 round-robin，因为 blocking L1 不会在未响应时连续发新事务。

### 4.2 Home → L1D grant

| 字段 | 规则 |
|---|---|
| `dstHart` | 必须等于请求 hart |
| `txnId/lineAddr` | 必须匹配锁存的 request |
| `grantState` | P4 只允许 `S` 或 `M`；不得发 `E` |
| `hasData` | miss/owner transfer 必须带整行；已有 S 的 S→M upgrade 可不带数据 |
| `error` | refill/writeback 失败时为 1，L1 不得安装/覆盖目标行 |
| `valid/ready` | backpressure 时 valid 和 payload 保持，handshake 后只能响应一次 |

### 4.3 Home → L1D probe

| opcode | 目标状态与数据规则 |
|---|---|
| `ProbeInv` | S→I；若异常遇到 M，必须返回最新数据，不得静默丢失 |
| `ProbeToS` | M→S 并带最新整行；P4 MSI 中正常不会有 E owner |
| `ProbeRecallInv` | M→I，必须带最新整行 |

`dstHart`、`txnId`、`lineAddr` 必须正确。Home 可以同一周期向多个目标拉高 probe `valid`；每个端口独立 backpressure，已 handshake 的目标从 `probeBitmap` 清除，未 handshake 的 payload 保持。

### 4.4 L1D → Home probe response

| 字段 | 规则 |
|---|---|
| `srcHart` | 必须等于对应端口号 |
| `txnId/lineAddr` | 必须匹配当前 probe |
| `ack` | 正常 response 必须为 1 |
| `hasData/lineData` | dirty owner downgrade/recall 必须返回最新整行 |

Home 仅对 `probeAckBitmap` 中仍待响应的端口拉高 response `ready`。重复、错误 hart、错误 txnId、错误 lineAddr 都必须 assertion fail，不能被忽略。

### 4.5 CPU pulse、I$ pulse 与 MMIO

- L1D CPU-facing 接口仍是一周期 pulse，不得擅自改成 ready/valid。
- 一个 L1D 只允许一个 CPU operation outstanding；probe 竞争时现有 one-entry CPU skid 必须保留。
- I$ refill 仍是一周期 pulse；Home 每 hart 保留一个 pending latch，busy 时也必须捕获，第二个未完成 pulse 应 assertion。
- I$ 不进入 D$ sharer bitmap。
- uncached/MMIO 不经过 L2 coherence；每 hart pulse 由 `BreezeMmioArbiter` 的 per-hart pending latch 捕获，再串行送 Wishbone。

---

## 5. 冻结的稳定状态与不变量

### 5.1 L1D MSI 状态编码

当前 DCache 用 metadata 组合编码状态：

| 状态 | valid | exclusive | dirty |
|---|---:|---:|---:|
| I | 0 | don't care/0 | 0 |
| S | 1 | 0 | 0 |
| M | 1 | 0 | 1 |

`exclusive` 位为 P5 MESI 预留；P4 grant 不得设置 E，因此正常 P4 数据行不应出现 `valid=1, exclusive=1, dirty=0`。

### 5.2 L2/Home 目录状态

| 目录状态 | `sharers` | `ownerId` | 最新数据位置 |
|---|---|---|---|
| `NONE` | `0000` | 无意义，写 0 | L2 数据最新 |
| `SHARED` | 非零 4-bit bitmap | 无意义，写 0 | L2 数据最新，bitmap 中 L1 均为 S |
| `UNIQUE` | `0000` | 0..3 中唯一一个 | owner L1 数据权威，L2 可能旧 |

必须保持：

- 同一行最多一个 M owner；
- `SHARED` 无 owner；
- `UNIQUE` 无 sharer；
- `ownerId < 4`；
- sharer bitmap 不得出现第 4 bit 以外的信息；
- `probeAckBitmap != 0` 时不得 GrantM；
- owner 最新数据未回收时不得把 line 交给新 owner；
- dirty/UNIQUE victim 未安全落到 L2/内存前不得覆盖；
- 一个 CPU request 只能产生一次 response。

P4 应在 `BreezeL2Home.scala` 补上与本阶段有关、可以在 RTL 内自证的 invariants。不能自证“真实 L1 状态等于 bitmap”的部分由 test harness 和 firmware oracle 证明。

---

## 6. L2/Home 主状态机合同

不得为了 P4 重命名或整体重写状态机。当前状态集合保持：

```text
Idle -> LookupRead -> Compare
                    -> ProbeReq -> ProbeWait -> HitUpdate
                    -> VictimWrite -> MemRead
                    -> SendGrant / SendPutAck / SendError -> Idle
```

### 6.1 仲裁和 lookup

1. `Idle`：coherence request 优先于 pending I$；同类中最低 hart 优先。
2. 只对被选端口 `ready=1`，锁存 `reqHart/op/txnId/lineAddr/data`。
3. `LookupRead` 发同步 SRAM read。
4. `Compare` 决定 hit/miss、目录转换、是否需要 probe/eviction/memory refill。

### 6.2 hit 转移表（P4 MSI）

| request | old dir | 动作 | new dir | grant |
|---|---|---|---|---|
| GetS | NONE | 不 probe | SHARED `{requester}` | S + data |
| GetS | SHARED | OR requester bit | SHARED `old | requester` | S + data |
| GetS | UNIQUE(other) | `ProbeToS(owner)`，等 Ack/dirty data | SHARED `{oldOwner, requester}` | S + latest data |
| GetM | NONE | 不 probe | UNIQUE requester | M + data |
| GetM | SHARED | `targets=sharers & ~requester`；全部 `ProbeInv` Ack 后更新 | UNIQUE requester | M；已有 S 可无 data |
| GetM | UNIQUE(same) | 权限重确认 | UNIQUE same | M，可无 data |
| GetM | UNIQUE(other) | `ProbeRecallInv(owner)` 并回收数据 | UNIQUE requester | M + latest data |
| PutS | SHARED/UNIQUE source 合法 | 清 source | NONE 或剩余 SHARED | PutAck |
| PutM | UNIQUE owner 合法 | 最新数据写入 L2、`dirtyToMemory=1` | NONE | PutAck |

P4 不允许 `GetS/NONE -> UNIQUE/E`，那是 P5。

### 6.3 miss/eviction 转移

1. invalid victim：直接 `MemRead`。
2. victim=`NONE` 且 clean：直接 `MemRead`。
3. victim=`NONE` 且 dirtyToMemory：先 `VictimWrite`，成功后 `MemRead`。
4. victim=`SHARED`：向 bitmap 全部 hart 发 `ProbeInv`，收齐 Ack；必要时写回，再 refill。
5. victim=`UNIQUE`：只向 owner 发 `ProbeRecallInv`，必须拿到数据；把回收数据写入 L2 transaction context/array，必要时写回，再 refill。
6. memory write error：旧 victim 保持有效，目录降为一致的 `NONE + dirtyToMemory`，请求者收到 error，不能安装新行。
7. refill error：不得安装失败行；请求者收到 error。

### 6.4 probe barrier

`ProbeReq` 只负责逐端口完成 probe handshake；`ProbeWait` 只负责逐端口收 Ack/data。三个目标可不同周期 ready，三个 response 可乱序返回。只有 `nextAck==0` 才能进入 `HitUpdate` 或 victim 后续状态。不得用“发出了三个 probe”代替“收到了三个 Ack”。

---

## 7. 必须先写的测试与 oracle

不要先修改 RTL。先把以下测试写成能独立失败的 oracle，再依据失败修 RTL。若当前参数化 RTL 已经通过某项测试，不得为了制造代码量而改 RTL。

### 7.1 新增四核 Home 测试文件

建议新增：

```text
design/src/test/scala/cache/BreezeL2HomeSmallSpec.scala
```

不要直接复用当前 `L2HomeHarness` 的单个全局 `probePending`：它一次只能模拟一个 probe，会在 Home 同时向三个 hart 发 probe 时误报。新建或重构为 **每 hart 独立 probe pending/latency/ready/response** 的 harness，并提供：

- `probeReadyMask`：指定各端口何时接受 probe；
- per-hart response delay/order；
- request/grant/probe/response/Wishbone event log；
- 每 hart grant count；
- 每行的 mock L1 state/data；
- 有界 cycle watchdog；
- 检查 valid && !ready 时 payload 稳定。

small L2 为 64 KiB、8-way、32 B line，共 256 sets。构造同 set 地址时使用：

```text
set stride = 256 * 32 = 8192 bytes
tag starts at address bit 13
addr = 0x80000000 + tag * 8192 + set * 32
```

必须包含以下 case：

#### S1：四路同时 GetS 不丢请求

- hart0..3 同周期对同一行保持 GetS valid；
- Home 可按固定优先级串行接受；
- 每 hart 恰好一次 accept、一次 grant；
- 只有第一个请求产生 4 个 memory read beats；
- 四个 L1 最终都为 S、数据完全相同；
- 不出现 duplicate grant。

#### S2：`1111` sharing 和三 Ack barrier

- 先让 hart0..3 都持有同一行 S；
- hart0 请求 GetM；
- 预期 probe 目标严格为 hart1、hart2、hart3，opcode=`ProbeInv`；
- 刻意让三个 probe 在不同周期 ready；
- 刻意让 response 以 3、1、2 顺序返回；
- 在 hart2 最后一个 Ack handshake 前，hart0 grant.valid 必须始终为 0；
- 最后 Ack 后只给 hart0 一次 M grant；
- hart1..3 全为 I，hart0 为 M。

#### S3：高 hart ID dirty ownership transfer

- hart3 GetM 并在 mock L1 修改整行；
- hart1 GetM；
- 只允许向 hart3 发一个 `ProbeRecallInv`；
- response `srcHart=3`、txnId/lineAddr 匹配并带最新数据；
- grant 只给 hart1，数据等于 hart3 最新行；
- hart3 为 I、hart1 为 M；
- 再让 hart2 GetS，验证返回的仍是最新行，并把 owner/reader降为 S/S。

#### S4：四 sharer inclusive eviction

- 让 target line 被四个 hart 共享；
- 填满同一 L2 set 的其他 7 way，再访问第 9 个 tag 触发 target victim；
- 记录四个 `ProbeInv`，每个 hart 恰好一次；
- Ack 收齐前不得开始覆盖 victim 或 Grant 新请求；
- eviction 后四个 mock L1 都不含 target；
- 重新访问 target 必须发生新的 4-beat memory refill。

#### S5：高 hart ID UNIQUE dirty victim eviction

- hart3 持有 M victim 并修改整行；
- 触发同 set replacement；
- 只 probe hart3，必须收到 data；
- Wishbone writeback 的四个 64-bit beat 必须来自回收后的最新整行，地址递增且各一次；
- memory error case 中不得覆盖/丢失该 dirty victim。

#### S6：四 hart I$ pending 不进入 sharer

- Home busy 时分别给四个 I$ 输入一个 pulse；
- 每个 pulse 恰好一次 response；
- I$ refill 不设置任何 D$ sharer bit；
- 随后 D$ GetS 不应 probe I$。

### 7.2 DCache 2-bit hart ID 测试

在新 small spec 或现有 coherent spec 增加一个最小定向测试：

- instantiate coherent DCache with `hartId=3, hartIdWidth=2`；
- GetS/GetM request 的 `srcHart` 必须为 3；
- 收 probe 后 response 的 `srcHart` 必须为 3；
- txnId/lineAddr echo 正确；
- 不改变现有 CPU pulse/probe skid 语义。

### 7.3 runner 自测试

扩展 `sim/litex/test_run_multicore.py`，至少覆盖：

- small profile marker：harts=4、l2Bytes=65536；
- small profile file 缺失/错值必须 nonzero；
- small 已注册测试能走到 fake child；
- dual-only T4 测试不能被 small 错误复用；
- missing PASS、FAIL、fatal、timeout 的既有测试继续通过。

runner 自测试不得依赖真实 RTL，但必须执行真实 `run_multicore.main()` 控制流。

### 7.4 firmware oracle

新增独立四核应用，建议：

```text
software/breeze-mcu/apps/multicore_t5.c
```

不要把 `multicore_t4.c` 的 `BREEZE_NUM_HARTS==2` 条件删掉后硬复用；保留 P3 oracle 不变，避免四核修改污染双核基线。

四核 firmware 必须：

- compile-time 要求 `BREEZE_NUM_HARTS==4`；
- 不使用 A 指令；
- shared control 的每个字段只有一个 writer；
- 每 hart 使用独立 done/result slot，避免多 writer data race；
- `fence rw,rw` 只表达发布顺序，注释明确说明 coherence 才提供可见性；
- 所有 spin loop 有有限计数，超时返回不同错误码；
- hart0 输出 expected/actual mask 和关键 expected/actual value；
- firmware 返回值决定真实 completion PASS/FAIL，不能只打印字符串。

至少注册以下 small 场景：

```text
boot
small-sharing
small-upgrade
small-dirty-transfer
small-same-line
small-same-line-race
small-l2-eviction
```

场景语义：

- `small-sharing`：hart1..3 都读到 hart0 发布的值，done mask=`0xe`，最终全 hart 可重读一致；
- `small-upgrade`：四 hart 先共享，hart0 再写新值，hart1..3 均读到新值；三 Ack 的精确时序由单元测试证明；
- `small-dirty-transfer`：hart3 写最新值，hart1 接管/读取，hart0 最终核对；
- `small-same-line`：四个 64-bit word 位于同一 32 B line，按受控阶段由不同 hart 更新，最终四个 word 都保留；
- `small-same-line-race`：四 hart 从 barrier 同时发同 line 请求，每 hart 单独报告值；
- `small-l2-eviction`：验证 64 KiB L2 几何下的 dirty data integrity。

现有 `l2_eviction.c` 把 set stride 固定为 2048，只适用于 64-set single L2。P4 必须按 profile 参数化：

```text
set stride = L2 capacity / L2 ways
single: 16384 / 8 = 2048
dual:   32768 / 8 = 4096
small:  65536 / 8 = 8192
```

可以让 `run_cluster_mcu.py` 传 `BREEZE_L2_BYTES`，由 firmware 推导 stride；不得继续用 2048 却声称验证了 small L2 eviction。

---

## 8. 预计修改文件白名单

初始允许：

```text
design/src/test/scala/cache/BreezeL2HomeSmallSpec.scala        # new
design/src/test/scala/cache/BreezeDCacheCoherentSpec.scala     # 仅高 hart ID 定向测试
design/src/main/scala/cache/BreezeL2Home.scala                 # 仅测试证明需要的 P4 修复/断言
design/src/main/scala/cache/BreezeDCache.scala                 # 默认不改；只有定向失败才允许
sim/litex/run_multicore.py
sim/litex/test_run_multicore.py
sim/litex/run_cluster_mcu.py
software/breeze-mcu/apps/multicore_t5.c                        # new
software/breeze-mcu/apps/l2_eviction.c
```

条件允许（只有 compile/elaboration 明确失败并给出原因时）：

```text
design/src/main/scala/bus/BreezeMmioArbiter.scala
design/src/main/scala/top/BreezeMulticoreClusterWishbone.scala
litex_wrapper/flow/cluster.py
sim/litex/multicore_sim.py
software/breeze-mcu/runtime/start.S
software/breeze-mcu/link.ld
```

不在白名单的文件不得修改。需要新增文件时，先说明“为什么现有接口无法完成”和新增文件的单一职责；不要扩大到 P5+。

---

## 9. 实施顺序与硬门槛

### G0：只读基线与白名单

本地完成：

```bash
git status --short --branch
git rev-parse HEAD
git rev-parse origin/feat/multicore-1-2-4
git log -1 --oneline --decorate
```

门槛：HEAD/remote 一致；只有已知 untracked docs；无 tracked diff。否则停止。

### G1：测试先行

本地只写第 7 节测试、harness 和 firmware oracle，不运行构建。人工检查：

- 每项 test 会因具体错误行为失败；
- 不只检查自己写入的 PASS 文本；
- request/grant/probe 次数和地址有明确断言；
- 三 Ack barrier 有“最后 Ack 前 grant=0”的逐周期断言；
- high hart ID 2-bit 路径被覆盖；
- loop 全都有上限。

形成 tests-first commit，或至少保留清晰的 tests-first diff 边界。

### G2：最小实现

只修测试暴露的问题。优先检查：

- `sharerWidth=numHarts` 是否在所有寄存器、mask、shift、loop 中保持 4 bit；
- `hartIdWidth=max(1, log2Ceil(numHarts))` 是否为 2 bit；
- `hartBit` 动态 shift 是否被错误截断；
- `PriorityEncoder` 和动态 Vec index 宽度；
- probe send bitmap 与 Ack bitmap 是否独立；
- 同周期最后 response data 是否被旧寄存器值覆盖；
- victim recall data 是否真正进入 writeback beats；
- grant payload 在 backpressure 时是否稳定；
- startup stack 和 linker 上界是否容纳四个 32 KiB slice。

禁止通过删除 assertion、放松 expected value、减少 target hart、串行化 firmware 以避开并发，或无依据增加 timeout 来“修复”。

### G3：本地提交与 push

逐文件 staging，检查 staged diff，无 docs/生成物后 commit。然后：

```bash
git push origin feat/multicore-1-2-4
```

push 失败则报告 `BLOCKED: local push`，保留本地 commit；不得改由 Alan push。

### G4：Alan 同步

两跳登录：

```bash
ssh clawbot
ssh -p 2286 chen@localhost
```

身份探针后：

```bash
cd /home/chen/FUN/flow
git status --short --branch
git fetch origin feat/multicore-1-2-4
git switch feat/multicore-1-2-4
git pull --ff-only origin feat/multicore-1-2-4
git rev-parse HEAD
git status --short --branch
```

Alan HEAD 必须等于本地 push SHA，且 pull 前工作区 clean。否则停止，不清理、不覆盖。

### G5：Alan 编译和定向测试

新 shell 初始化：

```bash
source /home/chen/miniforge3/etc/profile.d/conda.sh
conda activate flow
source /home/chen/FUN/env.sh
export PATH=/home/chen/.local/share/coursier/bin:$PATH
cd /home/chen/FUN/flow
```

依次执行并分别保存 exit code：

```bash
python3 -m unittest sim/litex/test_run_multicore.py -v
cd design
sbt build
sbt 'testOnly flow.cache.BreezeL2HomeSmallSpec'
sbt 'testOnly flow.cache.BreezeL2HomeSpec flow.cache.BreezeDCacheCoherentSpec flow.config.BreezeCoreConfigSpec'
sbt test
```

任何失败都属于 `FAILED`，不是 `BLOCKED`。保留日志，回本地修复、commit、push，再新建证据目录重跑。

### G6：Alan elaboration

最终 SHA 上分别生成，不能复用旧 RTL：

```bash
cd /home/chen/FUN/flow
python3 sim/litex/run_multicore.py --profile single --core-preset gshare --elaborate
python3 sim/litex/run_multicore.py --profile dual   --core-preset gshare --elaborate
python3 sim/litex/run_multicore.py --profile small  --core-preset gshare --elaborate
python3 sim/litex/run_multicore.py --profile small  --core-preset baseline --elaborate
```

检查 `cluster-profile.txt` 的 profile、numHarts、L2 bytes、preset；文件生成不等于仿真 PASS。

### G7：Alan 仿真矩阵

small 必测：

```bash
python3 sim/litex/run_multicore.py --profile small --test boot                 --core-preset gshare --timeout 600
python3 sim/litex/run_multicore.py --profile small --test small-sharing        --core-preset gshare --timeout 600
python3 sim/litex/run_multicore.py --profile small --test small-upgrade        --core-preset gshare --timeout 600
python3 sim/litex/run_multicore.py --profile small --test small-dirty-transfer --core-preset gshare --timeout 600
python3 sim/litex/run_multicore.py --profile small --test small-same-line       --core-preset gshare --timeout 600
python3 sim/litex/run_multicore.py --profile small --test small-same-line-race  --core-preset gshare --timeout 600
python3 sim/litex/run_multicore.py --profile small --test small-l2-eviction    --core-preset gshare --timeout 600
```

single 回归：boot、generic、l2-eviction。

dual 回归：boot、sharing、upgrade、dirty-read、dirty-transfer、same-line、same-line-race。

legacy 回归：`run_mcu.py` 的 generic、timer direct、uart direct，显式 `--core-preset gshare`。

若 RTL/runner 能把 `--elaborate` 与首个 test 合并，应确保每个 profile 的 RTL marker 来自同一最终 SHA。不得用一个 profile 的 build 目录跑另一个 profile。每个 profile/preset/test 使用独立 output dir。

---

## 10. Alan 证据格式

最终候选 SHA 建立新目录，例如：

```text
/home/chen/FUN/flow-runs/20260818Txxxxxx-p4-small-<shortsha>/
```

必须包含：

```text
meta.txt
commands.txt
exit-codes.tsv
summary.md
logs/<one-command-one-log>.log
```

`meta.txt` 至少记录：hostname、whoami、uname、date、branch、full SHA、git status、Python/SBT/Java/Verilator/LiteX 路径和版本。

`exit-codes.tsv` 每行：

```text
name<TAB>exit_code<TAB>log_path
```

执行带日志的命令时必须保留真实退出码；如果用 `tee`，先启用：

```bash
set -o pipefail
```

PASS 同时要求：

- shell exit code=0；
- SHA/profile marker 正确；
- runner verified PASS；
- firmware expected/actual/mask 正确；
- 无 watchdog、FAIL、`%Error`、`assertion failed`、`fatal error:`；
- test 数量和 suite 数量已记录；
- small boot mask=`0xf`，fail mask=`0`。

不要把 warning 自动写成 FAILED，但必须审查动态 Vec index、width truncation、unconnected signal 等与 4-hart 参数化直接相关的 warning；相关 warning 未解释时不得签收。

---

## 11. 失败决策树

1. **Scala compile/type/width 失败**：回本地最小修复；不得在 Alan 编辑。
2. **unit test oracle 失败**：先判断 harness 是否符合 ready/valid；给出 cycle/event log，再修 RTL 或 harness。不得只改 expected。
3. **最后 Ack 前出现 GrantM**：Home 协议 bug，停止仿真扩展，先修 `probeAckBitmap`/state transition。
4. **hart2/3 数据错、hart0/1 正常**：优先检查 hartIdWidth、dynamic shift、Vec index、端口号与 src/dstHart。
5. **small elaboration 失败而 dual PASS**：优先检查 4-entry Vec 和 2-bit index，不得降成 2 hart。
6. **firmware spin timeout**：先用每 hart phase/done/mask 诊断卡在哪个发布点；不能直接扩大循环上限。
7. **outer watchdog timeout**：先确认 MCU cycle watchdog、最后 marker、进程是否被 kill/reap；不得仅把 600 改成 3600。
8. **dirty victim 数据错**：核对 recall response 当周期数据是否进入 `victimDataReg` 和 Wishbone beat，而不是旧 L2 array data。
9. **P3 回归失败**：P4 FAILED；必须修复或回退相关 P4 改动，不能只保留 small PASS。
10. **SSH/push/tool/disk 外部问题**：才可写 BLOCKED，并给出原始错误与已尝试的安全检查。

同一根因连续失败时保留每个 SHA/日志，不覆盖旧证据。

---

## 12. 最终交付报告模板

执行 agent 最后只按以下结构报告，然后停止：

```text
Verdict: P4 PASS / FAILED / BLOCKED

Branch:
Final local/GitHub/Alan SHA:
Base SHA:

Commits:
- <sha> <subject> <files>

Scope proof:
- no docs committed
- no generated artifacts committed
- no P5/P6/P8 work

Protocol implementation:
- 4-bit sharers:
- 2-bit hart ID:
- 3-Ack barrier:
- dirty owner transfer:
- inclusive eviction:

Tests:
- runner unittest: x/x
- P4 targeted Scala: x/x
- full sbt: suites/tests
- elaboration: single/dual/small gshare, small baseline
- small simulations: each command + exit + marker + expected/actual
- single/dual/legacy regressions: each result

Evidence root:
Known limitations:
Next authorized stage: none; waiting for review
```

若任一必测项缺失，Verdict 不能写 PASS。

---

## 13. 可直接复制给下一位 agent 的短提示

```text
你在 /home/chen/leisure/flow 的 feat/multicore-1-2-4 分支上继续 Flow 多核工作。本轮只做 P4 四核 small-profile MSI，不做 MESI E、RV64A、CLINT/IPI 或 P5+。

先完整阅读 docs/agent-collab/p4-small-msi-implementation-handoff.md，并严格按其中的阅读顺序、接口协议、状态机、测试 oracle、文件白名单、Git/Alan 边界和停止条件执行。当前锚点是 fbcc0e89e84d7f066eba9be88f3a5269528d0603，但必须先重新核对本地、origin 和 Alan SHA。

所有源码/测试只在本地修改和 commit，从本地 push；只有 Alan /home/chen/FUN/flow 可以运行 Python 自测试、SBT、交叉编译、elaboration 和 LiteX/Verilator 仿真。Alan 不得编辑、commit 或 push。GitHub 是唯一同步通道。严禁 git add . / -A、提交 docs、scp 源码到 Alan、reset --hard、clean -fd。

测试先行：先实现四 hart 独立 probe pending/backpressure/乱序 Ack harness，覆盖 1111 sharing、三 Ack 前禁止 GrantM、高 hart ID dirty transfer、四 sharer inclusive eviction、UNIQUE dirty victim 和四 I$ pending；再做最小 RTL 修复。新增独立 multicore_t5.c，不破坏 multicore_t4.c。small L2 eviction stride 必须按 64 KiB/8-way 推导为 8192，不能沿用 single 的 2048。

最终在 Alan 的同一 final SHA 上跑完整 targeted/full test、1/2/4 elaboration、small 七项仿真、single/dual/legacy 回归，并保存 meta/commands/exit-codes/summary/logs。完成后按交接书第 12 节报告并停止，等待审核，不进入 P5，不 merge main。
```
