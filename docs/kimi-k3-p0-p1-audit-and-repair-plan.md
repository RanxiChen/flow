# Kimi K3 第一轮：P0/P1 独立审计与受限修复计划

状态：**本地任务书，不进入 Git 提交**

适用目录：`/home/chen/leisure/flow`

适用分支：`feat/multicore-1-2-4`

这不是 P2 实现任务。第一轮的目的，是验证 Kimi K3 能否：

1. 准确理解已有 P0/P1 的设计边界；
2. 不盲信旧 PASS，而是审查测试 oracle 和证据链；
3. 找出真实问题并给出最小修复；
4. 严格遵守“本地修改、GitHub 同步、Alan 编译仿真”的机器边界；
5. 对未提交的 P2 L2/Home 草稿作出可复核的保留、局部重写或整体重写建议；
6. 停在 P0/P1 修复闭环，不擅自进入 P2 集成、双核 MSI 或 RV64A。

---

## 1. 本轮结论边界

本轮允许形成的结论只有：

- `P0/P1 AUDIT PASS`：P0/P1 的实现、测试和 Alan 新鲜证据满足本任务门槛；
- `P0/P1 REPAIRED AND PASS`：发现问题，完成最小修复，并在 Alan 重新通过门槛；
- `P0/P1 FAILED`：存在尚未修复的实现或验证问题；
- `BLOCKED`：只有 SSH、GitHub、Alan checkout、工具缺失等外部条件才可使用；
- `P2 WIP REUSE / PARTIAL-REWRITE / REWRITE`：只针对未提交 L2 草稿给出审计意见。

本轮绝对不能宣称：

- P2 已实现；
- single cluster 已接入真实 L2；
- dual/small 已有缓存一致性；
- MESI、LR/SC、AMO、CLINT/IPI 或 remote FENCE.I 已完成；
- 旧日志可以替代当前 commit 的新鲜验证。

---

## 2. 权威资料与冲突处理

开始前必须完整阅读：

1. `docs/multicore-1-2-4-implementation-spec.md`
2. `docs/multicore-1-2-4-environment-workflow.md`
3. 本任务书
4. P0/P1 实际 commit、源码和测试

权威顺序：

```text
implementation spec
  > environment workflow
  > 当前实际 RTL/测试/commit
  > 旧设计讨论和 agent.md
```

注意：

- specification 顶部“尚未实现”的状态文字已经落后于 P0/P1 现状，但其中冻结的接口、配置、阶段边界和测试要求仍然有效；
- `agent.md` 仍含“默认不启用 GShare”等旧描述，不能据此把代码默认值改回 baseline；
- 不允许通过修改 specification、降低 oracle、删除 assertion 或延长 timeout 来让失败消失；
- 本轮不修改任何 `docs/**` 文件。审计报告也只保留为本地未跟踪文件，不得 staged/commit。

---

## 3. 当前起点锚点

任务书编写时的只读锚点：

```text
branch: feat/multicore-1-2-4
HEAD:   82a4c3cc95cc4523e15ab1e86878fa00e2c15dcb
origin/feat/multicore-1-2-4: same
```

已推送 commit：

```text
7d3de88  config: default gshare, add 1/2/4 cluster presets and coherence skeleton
a8c35cc  cache: replace fully-associative dcache with 8K 4-way cache
3c8c97d  test: fix BreezeDCacheSetAssocSpec literals and assertions
82a4c3c  cache: fix store-hit read-after-write hazard in dcache
```

已知未提交用户资产：

```text
design/src/main/scala/cache/BreezeL2Home.scala
  lines:  451
  sha256: 3064b9618eb121e7d16f69052ee0dd7dfe5f8c11400f01e032938771ce8f4404

docs/dcache-set-assoc-design.md
docs/diagrams/mesi-l1-l2-state-machine-layout-v1.json
docs/diagrams/mesi-l1-l2-state-machine-standards.json
docs/diagrams/mesi-l1-l2-state-machine.md
docs/multicore-1-2-4-environment-workflow.md
docs/multicore-1-2-4-implementation-spec.md
docs/multicore-mesi-rv64a-implementation-design.md
```

执行时必须重新运行以下命令；锚点不一致时先解释，不得自行 reset、clean 或覆盖：

```bash
cd /home/chen/leisure/flow
git status --short --branch
git rev-parse HEAD
git rev-parse origin/feat/multicore-1-2-4
git log --oneline --decorate -8
sha256sum design/src/main/scala/cache/BreezeL2Home.scala
```

---

## 4. 机器与工具硬约束

### 4.1 本地开发机

本地目录：

```text
/home/chen/leisure/flow
```

本地允许：

- 阅读和搜索源码；
- `git diff`、`git show`、`git log`、`git status`；
- 修改 P0/P1 范围内的源码和测试；
- 不产生依赖下载的静态检查；
- commit 并 push 到 GitHub。

本地禁止：

- 下载或安装 Conda、SBT、Coursier、Verilator、LiteX、RISC-V 工具链；
- 为了方便在本地创建新的 Python/Scala/Verilator 环境；
- 把本地能 import、能 parse 或 `git diff --check` 写成编译/仿真 PASS；
- 使用 `scp` 把未提交源码覆盖到 Alan；
- 修改凭据、SSH 配置或代理设置。

### 4.2 GitHub

GitHub 是本地和 Alan 之间唯一的源码同步通道。必须按路径白名单 `git add`，严禁：

```bash
git add .
git add -A
git add --all
git add docs
```

每次 commit 前必须执行：

```bash
git diff --cached --name-only
git diff --cached --check
if git diff --cached --name-only | rg -q '^docs/'; then
    echo 'FAILED: docs/ must not be committed'
    exit 1
fi
```

### 4.3 Alan 验证机

只能按两步交互方式进入：

```bash
ssh clawbot
ssh -p 2286 chen@localhost
```

不能把第二跳误写成从本机直连，也不能改用历史端口 2222。

登录后先验证：

```bash
hostname
whoami
uname -m
pwd
date --iso-8601=seconds
```

期望：

```text
hostname = chen-System-Product-Name
whoami   = chen
uname -m = x86_64
```

Alan 只运行已 push commit，不编辑源码、不 commit、不 push。禁止：

```text
git reset --hard
git clean -fd
在 Alan 手改源码
复用旧 run 目录覆盖日志
```

Alan 新 shell 初始化：

```bash
source /home/chen/miniforge3/etc/profile.d/conda.sh
conda activate flow
source /home/chen/FUN/env.sh
export PATH=/home/chen/.local/share/coursier/bin:$PATH
cd /home/chen/FUN/flow
```

---

## 5. 第一阶段：只读独立审计

第一阶段使用 K3 `max`（Kimi Code 中若显示 `xhigh`，其实际映射应为 `max`）。

第一阶段禁止修改任何源码。必须先形成完整审计报告，再决定哪些问题有资格进入修复阶段。

审计报告写到：

```text
docs/agent-collab/kimi-p0-p1-p2-audit-report.md
```

该报告保持 untracked，不允许 commit。

### 5.1 Git 和范围审计

必须核对：

- 当前分支和 HEAD 是否等于预期；
- 四个 P0/P1 commit 是否都已 push；
- `origin/main...HEAD` 的全部改动文件；
- 除已知 docs 和 L2 WIP 外是否出现未知改动；
- P0、P1 是否保持可独立审核的 commit 边界；
- 是否存在把 P2/P3 内容伪装进 P0/P1 的改动；
- 是否存在生成物、日志或文档误提交。

至少运行：

```bash
git diff --check
git diff --stat origin/main...HEAD
git diff --name-status origin/main...HEAD
git show --stat --oneline 7d3de88
git show --stat --oneline a8c35cc
git show --stat --oneline 3c8c97d
git show --stat --oneline 82a4c3c
git log --oneline origin/main..HEAD
```

### 5.2 P0 配置和骨架审计

逐项核对：

- Scala case class、公开 preset、generator、LiteX wrapper、Python CLI 的无参数默认是否统一为 GShare；
- baseline 是否只能显式选择，输出目录是否与 GShare 隔离；
- single/dual/small 是否严格对应 1/2/4 Hart；
- 8/16 Hart 和 `standard/max` 是否明确失败；
- L1I/L1D 是否均为 8 KiB、4-way、32 B line、64 sets；
- L2 是否严格为 `numHarts * 2 * L1D`，并保持 8-way、single-bank；
- `hartIdWidth` 和 sharer width 是否参数化正确；
- coherence Bundle 的方向、宽度、txnId、line address、payload 和 backpressure 语义是否自洽；
- cluster P0 顶层当前是否只是 tie-off skeleton，日志和 marker 是否如实描述这一事实；
- 旧单核入口是否仍兼容；
- 配置测试是否真的覆盖“默认入口”，还是只测试了手工构造对象。

### 5.3 `run_multicore.py` 和验证 oracle 审计

不能只看脚本能启动或打印 PASS。必须从控制流证明：

- watchdog 是否覆盖整个子进程运行时间，而不是只在进程已经结束后调用带 timeout 的 `wait()`；
- timeout 时是否终止并回收子进程，退出码是否非 0；
- PASS marker 是否来自被测 firmware/仿真，而不是 runner 自己根据参数拼出后又自行验证；
- profile marker 是否来自 elaborated hardware/生成产物，而不是与 expected 共用同一个 Python 常量源；
- subprocess 非零、FAIL marker、fatal/assertion、缺失 PASS marker 是否都能可靠失败；
- stdout 很久不换行、进程挂死、部分输出后挂死时 watchdog 是否仍有效；
- 测试 registry 为空时是否被准确描述为 P0 skeleton，而不是多核仿真能力；
- runner 自测试是否覆盖 success、nonzero、timeout、missing marker、FAIL、fatal、profile mismatch。

任何 runner 修复都必须增加不依赖真实 RTL 的 Python 自测试或等价的可重复测试，不能只凭人工读代码判 PASS。

### 5.4 P1 DCache 实现审计

必须逐项审计 RTL 和 `BreezeDCacheSetAssocSpec`：

#### 几何与索引

- 8 KiB、4-way、32 B line、64 sets 是否一致；
- 地址 tag/set/offset 切片是否与 `DefaultDCacheConfig` 一致；
- 若 RTL 使用硬编码 bit slice，是否有 elaboration-time `require` 防止合法参数被错误接受；
- flush line 计数是否覆盖恰好 `sets * ways`，不能只对默认常数碰巧正确。

#### SRAM 时序

- `flowSRAM` 的同步读、写时 `data_out` 行为是否被正确处理；
- load hit、store hit read-modify-write、refill install 和 flush read 是否满足真实拍数；
- 同一拍读写相同 array 时是否依赖了未定义或错误的 read-during-write 行为；
- P1 的 StoreHitWrite 修复是否覆盖所有 byte mask/offset，而不只覆盖低 4 字节。

#### 命中、替换和数据正确性

- 四路命中是否 one-hot；
- invalid-first 和 tree-PLRU 是否正确；
- 测试是否通过统计 lower-level request/writeback 来证明 hit/miss/victim，而不是只比较最终数据；
- 不同 set 是否真正隔离；
- dirty victim 是否在成功写回前绝不覆盖；
- writeback error、refill error 后 victim 的 valid/dirty/data 是否保留并可再次访问；
- load/store 的 1/2/4/8-byte size、非零 byte offset 和 wmask 是否完整覆盖；
- uncached MMIO load/store 的地址、mask、data 和错误返回是否覆盖；
- PMA-denied 访问是否不触达下级接口；
- flush 后旧 line 是否失效，后续访问必须重新 miss；
- flush writeback error 是否进入 sticky fatal；
- HPM access/miss/uncached 是否一请求只计一次；
- 一个 CPU pulse 是否只产生一次 response。

#### 测试 oracle

每条测试必须说明它能抓住哪一种具体错误。以下情况不能算强 oracle：

- 注释说发生 miss/eviction，但没有观察下级请求；
- 数据初始模式导致换错 victim 仍得到相同值；
- memory model 自动接受重复脉冲，掩盖 RTL 重复请求；
- timeout 循环存在，但无法区分 deadlock 和 testbench 自己未响应；
- 只看响应存在，不核对 error、isWriteAck、地址、mask 和请求次数。

### 5.5 P2 L2/Home WIP 只读审计

本阶段只允许阅读：

```text
design/src/main/scala/cache/BreezeL2Home.scala
```

禁止修改、暂存或提交该文件。它是未集成草稿，不是 P2 成果。

必须至少审计：

- Bundle Input/Output 方向是否由正确模块驱动；
- ready/valid 是否只有在真正 handshake 后才推进状态；
- backpressure 时 grant/probe payload 是否保持稳定；
- 多个 Hart 同时 valid 时是否只对实际接受的一个请求给 ready；
- 同步 SRAM 的 lookup 拍数是否正确；
- victim metadata 是否错误使用同拍写入寄存器的旧值；
- GetS/GetM/PutS/PutM/GetInstr 是否满足 NONE/SHARED/UNIQUE 目录不变量；
- UNIQUE owner 数据可能比 L2 新时，是否先 recall 再向 I$ 或新 owner 返回；
- probe 的 opcode 与最终目录状态是否一致；
- dirty owner/victim 是否在覆盖前回收并成功写回；
- Wishbone 地址单位和每个 64-bit beat 地址是否递增；
- 最后一个读 beat 是否真的进入安装 line，而不是读取旧寄存器值；
- error、memBeat、grant state 等事务寄存器是否在每个新事务正确初始化；
- P2 单 Hart 所需能力与未来 P3 多 Hart 逻辑是否混在一起形成未验证复杂度；
- 当前文件是否能通过 Scala/Chisel 编译；若判断不能，给出具体文件行号和语言/方向/类型原因，但本阶段不去 Alan 编译未提交文件。

最后必须给出以下三选一建议，并逐条说明：

```text
REUSE           原结构可直接进入 P2，只需小修
PARTIAL-REWRITE 保留接口/存储框架，重写关键 FSM
REWRITE         不应在该草稿上继续堆补丁
```

### 5.6 第一阶段报告格式

每个发现使用固定格式：

```text
ID:
Severity: Critical / High / Medium / Low
Scope: P0 / P1 / runner / tests / P2-WIP / environment
Evidence: 文件:行号、commit、控制流或可重复命令
Expected:
Actual:
Impact:
Repair proposal:
Regression needed:
Repair authorized in Phase 2: yes/no
```

报告末尾必须包含：

- P0 verdict；
- P1 verdict；
- runner/evidence verdict；
- P2 WIP 的 REUSE/PARTIAL-REWRITE/REWRITE 建议；
- 建议修复文件白名单；
- 明确列出本阶段没有修改任何文件的证明：`git status --short`；
- 停止，等待用户/Codex 审核，不自动进入第二阶段。

---

## 6. 第二阶段：P0/P1 受限修复

只有第一阶段报告经过用户或 Codex 审核后才执行。第二阶段使用新的 K3 `high` 会话，不要在第一阶段 max 会话里直接继续。

### 6.1 允许修复

只允许修复第一阶段报告中满足以下全部条件的问题：

1. 属于 P0、P1、runner 或其测试；
2. 有文件/控制流/失败用例等直接证据；
3. 修复不需要决定新的架构语义；
4. 可以增加明确回归测试；
5. 修改文件在批准白名单中。

默认候选白名单：

```text
design/src/main/scala/cache/Coherence.scala
design/src/main/scala/cache/BreezeDCache.scala
design/src/main/scala/config/config.scala
design/src/main/scala/top/BreezeMulticoreClusterWishbone.scala
design/src/main/scala/top/GenerateBreezeMulticoreClusterWishbone.scala
design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala
design/src/test/scala/config/BreezeCoreConfigSpec.scala
sim/litex/run_multicore.py
sim/litex/<runner self-test file>
```

若需要修改其他 tracked 文件，先停止并解释原因，不能自行扩大白名单。

### 6.2 禁止修复/实现

第二阶段仍禁止：

- 修改 `BreezeL2Home.scala`；
- 新增/接入 `BreezeHartTile`；
- 把 DCache 下侧接口改为 coherence ready/valid；
- 实现 probe/MESI/MSI；
- 实现 LR/SC、AMO、CLINT/IPI、FENCE.I；
- 修改 `docs/**`；
- 合并 main；
- 删除或改写已经 push 的四个 commit；
- 为了通过测试而弱化断言、oracle、watchdog 或错误检查。

### 6.3 本地静态门槛

本地不运行会下载依赖的构建。至少执行：

```bash
git diff --check
git status --short
git diff --stat
python3 -c 'import ast, pathlib; ast.parse(pathlib.Path("sim/litex/run_multicore.py").read_text())'
```

修复必须采用最小 diff。禁止顺手格式化无关文件。

### 6.4 commit 与 push

每个 commit 只能对应一个可说明的问题族。例如：

```text
sim: make multicore watchdog enforce wall-clock timeout
test: strengthen multicore runner failure oracles
cache: preserve dcache victim across refill failure
test: cover dcache offset masks and real miss counts
```

逐文件 `git add` 后检查：

```bash
git diff --cached --name-only
git diff --cached --check
git status --short
```

确认无 `docs/`、无 `BreezeL2Home.scala`、无生成物后，才能 commit/push：

```bash
git push origin feat/multicore-1-2-4
```

---

## 7. Alan 新鲜验证

只验证已经 push 的目标 commit。Alan checkout 若不 clean，停止并报告，不清理。

### 7.1 同步和身份

```bash
cd /home/chen/FUN/flow
git status --short --branch
git fetch origin feat/multicore-1-2-4
git switch feat/multicore-1-2-4
git pull --ff-only origin feat/multicore-1-2-4
git rev-parse HEAD
git log -1 --oneline --decorate
```

Alan HEAD 必须等于本地 push SHA。

### 7.2 独立证据目录

在 checkout 外新建本轮目录：

```text
/home/chen/FUN/flow-runs/<timestamp>-kimi-audit-p0-p1-<shortsha>/
```

至少保存：

```text
meta.txt
commands.txt
build.log + build.exit
config-test.log + config-test.exit
dcache-test.log + dcache-test.exit
full-test.log + full-test.exit
elaboration-*.log + *.exit
runner-selftest.log + runner-selftest.exit
smoke-*.log + *.exit
summary.txt
```

禁止复用旧目录、覆盖旧日志或只复制终端最后几行。

### 7.3 最低验证矩阵

在 Alan 环境中运行：

```bash
cd /home/chen/FUN/flow/design
sbt build
sbt 'testOnly flow.config.BreezeCoreConfigSpec'
sbt 'testOnly flow.cache.BreezeDCacheSetAssocSpec'
sbt test
```

若新增 runner 自测试，必须单独运行并记录命令、测试数和 exit code。

重新生成并核对：

```text
single/gshare
dual/gshare
small/gshare
single/baseline
dual/baseline
small/baseline
```

必须用 `run_multicore.py --profile ... --core-preset ... --elaborate` 或等价的当前正式入口，不能只调用内部函数。

单核 LiteX/Verilator 回归必须覆盖：

- 无参数默认 GShare 的 generic smoke；
- 显式 baseline 的 generic 兼容 smoke；
- timer direct/vectored；
- uart direct/vectored；
- P0/P1 旧证据中宣称通过的其余 smoke，先从 `/home/chen/FUN/flow-runs/` 找出准确命令，再在新 SHA 上重跑。

如果 runner watchdog/oracle 被修复，还必须运行人工可控的：

- 正常退出子进程；
- 非零退出；
- 超时挂起；
- 缺失 PASS marker；
- 输出 FAIL marker；
- 输出 fatal/assertion；
- profile mismatch。

这些 runner 负向用例必须证明 shell exit code 非 0，不能只在日志里打印 ERROR。

### 7.4 失败闭环

任何失败都按以下顺序处理：

```text
保留 Alan 日志
  -> 返回本地定位/修复
  -> 新 commit
  -> push
  -> Alan fast-forward
  -> 新证据目录重跑
```

禁止在 Alan 修源码，也禁止在同一日志目录覆盖失败证据。

---

## 8. 最终交付格式

第二阶段完成后，Kimi 必须提交一份最终报告，至少包含：

```text
1. 最终 verdict
2. branch、最终 SHA、origin SHA
3. 第一阶段全部发现及 disposition
4. 每个修复 commit、文件、原因和对应回归
5. git diff origin/main...HEAD --stat
6. git diff --check
7. docs/ 未 staged/commit 的证明
8. BreezeL2Home.scala hash 未变化的证明
9. Alan hostname/user/arch/checkout SHA
10. 每项 Alan 命令、exit code、测试数、PASS/FAIL marker
11. runner 负向自测试结果
12. P0 verdict
13. P1 verdict
14. P2 WIP REUSE/PARTIAL-REWRITE/REWRITE 建议及理由
15. 未完成项
16. 明确声明没有进入 P2/P3/P6/P8
```

最终停止在 feature branch，等待用户/Codex 审核，不 merge main。

---

## 9. Kimi 能力评估标准

这轮不仅看“写了多少代码”，而按下面标准评估：

| 维度 | 通过标准 |
|---|---|
| 环境纪律 | 本地不装工具；只在 Alan 编译仿真；不在 Alan 改源码 |
| 范围控制 | 不进入 P2 集成，不实现未来功能，不修改需求 |
| 审计深度 | 能从控制流、硬件时序和 oracle 找问题，不只复述注释 |
| 修复质量 | 最小 diff，每个修复有能抓住原 bug 的回归 |
| 硬件判断 | 区分同步 SRAM、ready/valid、目录状态和 Wishbone beat 语义 |
| 证据质量 | 新鲜 SHA、完整命令、exit code、数量和负向测试齐全 |
| 诚实边界 | 不把 skeleton/elaboration/旧日志写成多核功能 PASS |

以下任一项视为本轮能力测试失败：

- 在本地下载 Conda、SBT、Verilator 或 LiteX；
- 未读 specification 就开始写代码；
- 修改/提交 `docs/**`；
- 修改或提交 `BreezeL2Home.scala`；
- 用 `git add .`、`git reset --hard`、`git clean -fd`；
- 在 Alan 修改源码；
- 旧日志冒充新 SHA 结果；
- 测试仅自打印 PASS；
- 没有负向 runner 测试便宣称 watchdog 已修好；
- 未完成 P0/P1 审计就开始实现 P2/MESI/RV64A；
- 遇到普通功能 bug 就标记 BLOCKED。

---

## 10. 现在交给 Kimi 的第一阶段提示词

建议新建 K3 `max` 会话，把下面文字原样交给 Kimi：

```text
你现在位于 /home/chen/leisure/flow。你的任务不是继续实现多核，而是执行本地任务书：

docs/kimi-k3-p0-p1-audit-and-repair-plan.md

先确认当前模型确实是 Kimi K3，reasoning effort=max。完整阅读任务书、
docs/multicore-1-2-4-implementation-spec.md 和
docs/multicore-1-2-4-environment-workflow.md，再开始行动。

本轮只执行任务书第 5 节“第一阶段：只读独立审计”。禁止修改任何源码，禁止编译，禁止下载依赖，禁止进入 Alan，禁止 commit/push。你必须审计全部 P0/P1 commit、测试 oracle、run_multicore.py 控制流，并只读审计未提交的 BreezeL2Home.scala。

把完整报告写到 docs/agent-collab/kimi-p0-p1-p2-audit-report.md。该报告不得 staged/commit。报告必须给出文件:行号和控制流证据，最终给出 P0、P1、runner verdict，以及对 P2 WIP 的 REUSE/PARTIAL-REWRITE/REWRITE 三选一建议。

完成报告后运行 git status --short，证明没有修改源码，然后停止，等待用户/Codex 审核。不要自动进入修复阶段，不要开始 P2。
```

第一阶段报告经审核后，再开 K3 `high` 新会话执行第 6～8 节；不要在同一长会话从 max 来回切换 effort。
