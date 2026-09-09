# Flow 1/2/4 核开发环境与执行约束

状态：**本地权威执行手册，不进入 Git 提交**

适用任务：从当前单核 Flow 出发，实现 `single=1`、`dual=2`、`small=4` 三种多核配置，包括组相联 L1D、共享 L2/Home、MSI→MESI、RV64A、CLINT/IPI 和多核仿真闭环。

配套 specification：`docs/multicore-1-2-4-implementation-spec.md`。

本手册和配套 specification 对本任务具有最高优先级。此前 Word、`docs/dcache-set-assoc-design.md`、`docs/multicore-mesi-rv64a-implementation-design.md` 和图稿只可作为背景，不得覆盖本手册已经冻结的 1/2/4 核范围、容量公式、远端路径或验证门槛。

## 1. 角色与机器边界

### 1.1 本地开发机

固定 checkout：

```text
/home/chen/leisure/flow
```

职责：

- 阅读本地 specification；
- 修改 Chisel、Python、软件和测试源码；
- 做不依赖完整环境的静态检查；
- 使用明确文件白名单暂存；
- commit 到任务 feature branch；
- 从本地 push 到 GitHub；
- 审查 Alan 返回的日志并在本地修复。

禁止：

- 不把 Alan 当作第二个开发 checkout；
- 不从 Alan 反向复制未经提交的源码作为主线；
- 不把 `docs/` 下的本地设计稿提交到 Git；
- 不在验证失败后绕过 GitHub 用 `scp` 覆盖 Alan 源码；
- 不在 `main` 上堆积尚未验证的大改动。

### 1.2 GitHub

固定 remote：

```text
https://github.com/RanxiChen/flow.git
```

职责：

- 本地开发机与 Alan 之间唯一的源码同步通道；
- 保存实现代码、测试代码和可执行脚本；
- 保存任务 feature branch 的提交历史。

GitHub commit/push 只证明源码已同步，不等于编译、仿真或语义 PASS。

### 1.3 Alan 验证机

固定 checkout：

```text
/home/chen/FUN/flow
```

职责：

- 从 GitHub fetch/pull 已提交源码；
- 运行 SBT 编译、测试和 RTL elaboration；
- 运行 LiteX/Verilator 仿真；
- 保存逐阶段日志、commit SHA、退出码和 PASS marker；
- 只把失败诊断和证据带回本地。

禁止：

- 不在 Alan 编辑源码；
- 不在 Alan commit 或 push；
- 不用 `git reset --hard`、`git clean -fd` 或覆盖式 checkout；
- 不在 worktree 非 clean 时 pull；
- 不把旧 build 目录的成功结果当成当前 commit 的结果；
- 不把“进程运行过”或“RTL 生成了”当成仿真 PASS。

## 2. SSH 路由

唯一批准的正常登录方式是两步交互登录：

```bash
ssh clawbot
```

进入 clawbot 后：

```bash
ssh -p 2286 chen@localhost
```

不得写成：

```text
本机直接 ssh -p 2286 chen@localhost
ssh clawbot -p 2286
历史端口 2222
其他未经本轮确认的公网 IP
```

首次第二跳可能要求确认 `[localhost]:2286` host key。正常交互确认后再继续；不要在正式流程中长期配置 `StrictHostKeyChecking=no`。

登录后必须执行身份探针：

```bash
hostname
whoami
uname -m
pwd
date --iso-8601=seconds
```

当前已验证期望值：

```text
hostname: chen-System-Product-Name
whoami:   chen
uname -m: x86_64
```

任一值不符合时停止，不要在未知机器继续。

## 3. 当前状态锚点

本手册编写时的只读快照：

```text
本地 /home/chen/leisure/flow:
  branch: main
  HEAD:   7b9420c65bab2207d10f4c008dfd38fc3fdad039
  local origin/main: same

GitHub refs/heads/main（从 Alan 查询）:
  7b9420c65bab2207d10f4c008dfd38fc3fdad039

Alan /home/chen/FUN/flow:
  branch: main
  HEAD:   f27a32b5f381638c714a99c60044b67f2164b434
  status: clean at probe time
```

这些 SHA 是环境快照，不是未来执行时可以跳过核对的常量。Alan 当前落后于 GitHub；执行 agent 必须通过正常 fetch/feature-branch checkout 同步，不能假设它已经更新。

## 4. `docs/` 永不进入本任务提交

### 4.1 当前真实 Git 状态

仓库的 `.gitignore` **没有**整体忽略 `docs/`。`.git/info/exclude` 当前只排除了：

```text
/docs/worklog.md
```

因此新的本地设计稿虽然显示为 untracked，但并没有自动防提交保护。

### 4.2 强制 staging 规则

只允许逐路径白名单暂存，例如：

```bash
git add design/src/main/scala/cache/Coherence.scala
git add design/src/main/scala/cache/BreezeDCache.scala
git add design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala
git add sim/litex/run_multicore.py
```

严禁：

```bash
git add .
git add -A
git add --all
git add docs
```

每次 commit 前必须运行：

```bash
git diff --cached --name-only
git diff --cached --check
```

再执行硬门槛：

```bash
if git diff --cached --name-only | rg -q '^docs/'; then
    echo 'FAILED: docs/ must not be committed'
    exit 1
fi
```

还要确认没有把生成物纳入提交：

```bash
if git diff --cached --name-only | rg -q '(^|/)(build|target|obj_dir)/|\.vcd$|\.log$|\.bin$|\.elf$'; then
    echo 'FAILED: generated artifact staged'
    exit 1
fi
```

### 4.3 允许提交的内容

- `design/src/main/scala/**`：实现源码；
- `design/src/test/scala/**`：Chisel/Scala 测试；
- `config/**`：机器可读取的平台/缓存配置；
- `litex_wrapper/**`：LiteX CPU/cluster/CLINT wrapper；
- `sim/**`：仿真 SoC、monitor、runner、测试输入；
- `software/**`：用于验证的裸机程序、startup、linker 和库；
- 根目录构建文件：仅当实现确实需要。

### 4.4 不允许提交的内容

- `docs/**` 的新增或修改；
- Word/PDF/图片讨论材料；
- Alan 日志和整套 build tree；
- 本地临时任务书；
- 波形、大型生成 RTL、Verilator obj_dir；
- token、密码、私钥、代理配置、主机指纹。

## 5. 分支与同步规则

### 5.1 任务分支

默认任务分支：

```text
feat/multicore-1-2-4
```

本地创建前先确认基线：

```bash
cd /home/chen/leisure/flow
git status --short --branch
git rev-parse HEAD
git rev-parse origin/main
```

工作区只允许存在用户已知的本地 `docs/` 草稿；任何未知 tracked diff 必须先停下审查。

若任务分支不存在：

```bash
git switch -c feat/multicore-1-2-4
```

若已经存在：

```bash
git switch feat/multicore-1-2-4
```

不得删除或重建一个含未审核提交的同名分支。

### 5.2 commit 原则

每个阶段一个或少量可独立审查的 commit。推荐阶段前缀：

```text
config: add single dual small cluster presets
cache: replace fully associative dcache with 8k 4-way cache
cache: add single-hart l2 home path
cache: add dual-hart directory MSI
cache: scale directory MSI to four harts
cache: add MESI exclusive state
isa: add RV64A LR SC and AMO
soc: add multi-hart CLINT and IPI fence flow
sim: close single dual small multicore regressions
```

不要把所有阶段压成一个不可审查的大 commit，也不要为了“提交整洁”改写已经推送且用于 Alan 验证的历史。

### 5.3 本地 push

本地完成静态门槛后：

```bash
git push -u origin feat/multicore-1-2-4
```

若本地 GitHub 网络/认证失败：

- 标记 `BLOCKED: local push`；
- 保留本地 commit；
- 不改用 Alan push；
- 不用 `scp` 绕过 GitHub；
- 不把未推送 commit 交给 Alan 验证。

### 5.4 Alan 只做 fast-forward 同步

在 Alan：

```bash
cd /home/chen/FUN/flow
git status --short --branch
git remote -v
```

若存在任何未知修改或 untracked 非生成资产，停止并报告，不清理。

首次切入任务分支：

```bash
git fetch origin feat/multicore-1-2-4
git switch -c feat/multicore-1-2-4 --track origin/feat/multicore-1-2-4
```

后续更新：

```bash
git switch feat/multicore-1-2-4
git pull --ff-only origin feat/multicore-1-2-4
```

每次运行前记录：

```bash
git rev-parse HEAD
git status --short --branch
git log -1 --oneline --decorate
```

Alan HEAD 必须等于本地已 push 的目标 commit；不一致则不运行。

## 6. Alan 软件环境初始化

每个新的 SSH shell 或 tmux pane 都从下面顺序开始：

```bash
source /home/chen/miniforge3/etc/profile.d/conda.sh
conda activate flow
source /home/chen/FUN/env.sh
export PATH=/home/chen/.local/share/coursier/bin:$PATH
cd /home/chen/FUN/flow
```

顺序不可省略：非交互 shell 若未先加载 `conda.sh`，直接 `source /home/chen/FUN/env.sh` 中的 `conda activate flow` 可能不生效，随后会出现 `ModuleNotFoundError: No module named 'litex'`。

每轮 Stage 0 工具探针：

```bash
echo "$CONDA_DEFAULT_ENV"
command -v python3
command -v litex_sim
command -v riscv64-unknown-elf-gcc
command -v riscv64-unknown-elf-objdump
command -v sbt
command -v java
command -v verilator
python3 -c 'import litex, migen; print(litex.__file__); print(migen.__file__)'
sbt --script-version
java -version
verilator --version
python3 --version
```

本手册编写时已验证：

```text
CONDA_DEFAULT_ENV=flow
python3=/home/chen/miniforge3/envs/flow/bin/python3
litex_sim=/home/chen/miniforge3/envs/flow/bin/litex_sim
riscv64-unknown-elf-gcc=/home/chen/FUN/riscv/bin/riscv64-unknown-elf-gcc
sbt=/home/chen/.local/share/coursier/bin/sbt
SBT=1.11.2
Java=OpenJDK 11.0.31
Verilator=5.028
Python=3.12.3
LiteX source=/home/chen/FUN/flow_litex/litex/litex
Migen source=/home/chen/FUN/flow_litex/migen/migen
```

版本漂移并不自动等于失败，但必须记录；出现编译/行为差异时不得拿旧版本日志替代。

## 7. 长任务与日志

在 Alan 上使用 tmux，避免 SSH 断开终止编译/仿真：

```bash
tmux new -s flow-multicore
```

证据目录放在 checkout 外：

```bash
mkdir -p /home/chen/FUN/flow-runs
```

每次运行使用独立目录，名称至少包含 UTC/本地时间、profile、阶段和短 SHA，例如：

```text
/home/chen/FUN/flow-runs/20260816-003000-p3-dual-a1b2c3d/
```

目录中保存：

```text
meta.txt          hostname/user/date/branch/SHA/tool versions
command.txt       完整命令
stdout.log        标准输出与标准错误
exit_code.txt     shell exit code
summary.txt       PASS/FAILED/BLOCKED 与 marker 摘要
```

日志不得以覆盖方式复用旧 run 目录。一个 run 只能对应一个 commit SHA、一个 profile 和一个明确命令。

## 8. 构建与验证分层

### 8.1 静态门槛（本地）

```bash
git diff --check
git status --short
git diff --stat
```

检查：

- 修改文件都在当前阶段白名单；
- 没有 docs staged；
- 没有未解释的大范围格式化；
- 没有生成物；
- 没有把未来 8/16 核、MSHR、banked L2 混进当前阶段。

### 8.2 Scala 编译（Alan）

```bash
cd /home/chen/FUN/flow/design
sbt build
```

只证明 production/test Scala 能编译，不证明单元测试或 RTL 仿真正确。

### 8.3 Chisel 测试（Alan）

定向测试优先：

```bash
sbt 'testOnly <exact-suite>'
```

当前阶段定向测试通过后运行：

```bash
sbt test
```

必须记录 suite/test 数量和 exit code；不能只截取最后一行 `All tests passed`。

### 8.4 RTL elaboration（Alan）

现有单核兼容入口：

```bash
cd /home/chen/FUN/flow/design
sbt elaborate
```

本任务新增 cluster generator 后，必须按配套 specification 对 `single/dual/small` 分别 elaboration。生成文件存在只算 elaboration PASS，不能算仿真 PASS。

### 8.5 LiteX/Verilator（Alan）

现有单核 runner 的显式 baseline 兼容探针：

```bash
cd /home/chen/FUN/flow
python3 sim/litex/run_mcu.py \
    --main software/breeze-mcu/apps/main.c \
    --core-preset baseline \
    --elaborate
```

当前源码的无参数默认值仍是 baseline；本任务 P0 必须把所有面向用户的默认值切到 GShare。切换以后，无 `--core-preset` 的单核/多核 runner 与无 predictor 参数的 generator 必须选择 `gshare`；`baseline` 继续作为显式兼容配置保留。最终必测主矩阵使用 GShare，baseline 只做明确标注的兼容回归。

现有中断 smoke：

```bash
python3 sim/litex/run_mcu.py --smoke timer --mtvec-mode direct --elaborate
python3 sim/litex/run_mcu.py --smoke uart  --mtvec-mode direct
python3 sim/litex/run_mcu.py --smoke timer --mtvec-mode vectored
python3 sim/litex/run_mcu.py --smoke uart  --mtvec-mode vectored
```

新增多核 runner 的命令和 PASS marker 由配套 specification 冻结。不得用旧单核 runner 的 PASS 代替 `dual/small` PASS。

## 9. PASS / FAILED / BLOCKED 定义

### PASS

同时满足：

- 运行的 SHA 与 GitHub/本地目标 SHA 一致；
- 命令 exit code 为 0；
- runner 打印当前 profile/config marker；
- watchdog 没有触发；
- firmware oracle 打印明确 PASS；
- 结果数值、hart mask、memory value 与预期一致；
- 没有被过滤掉的 assertion、`error:` 或 Verilator fatal。

### FAILED

包括：

- 编译失败；
- assertion/fatal；
- timeout；
- 数值或 hart mask 不符；
- 某目标 Hart 未启动/未 Ack；
- exit code 非 0；
- 使用了错误 profile、错误 SHA 或旧 RTL marker。

失败后：保留现场；本地修复；commit/push；Alan fast-forward；新建 run 目录重跑。禁止在同一个 run 目录覆盖日志。

### BLOCKED

只用于无法进入任务本身的外部条件，例如：

- 本地无法 push GitHub；
- Alan SSH 认证失败；
- Alan checkout 不 clean 且来源不明；
- 工具/依赖缺失；
- 磁盘不足；
- specification 出现必须由用户决定的冲突。

不得把功能 bug、测试失败或实现困难写成 BLOCKED。

## 10. Agent 执行纪律

执行 agent 必须：

1. 先读完本手册和配套 specification；
2. 在每阶段开始前列出允许修改文件；
3. 一次只推进一个阶段；
4. 每阶段先本地实现/静态审查，再 commit/push，再 Alan 验证；
5. 失败只回本地修复；
6. 不自行扩大到 8/16 核、Linux、S-mode、PLIC、DMA、MSHR 或 banked L2；
7. 不修改 `docs/` 以外的需求定义来“让测试通过”；
8. 不降低 oracle、删除 assertion、延长 timeout 来掩盖 deadlock；
9. 最终交付 feature branch、commit 列表和全部 Stage PASS 矩阵；
10. 停在等待 Codex/用户审核，不 merge main。

## 11. 最终交接清单

执行 agent 完成后必须给审核者：

- feature branch 名；
- 最终 commit SHA；
- 每阶段 commit 列表和修改文件；
- `git diff origin/main...HEAD --stat`；
- `git diff --check`；
- staged/committed 文件中无 `docs/` 的证明；
- Alan 身份、工具版本和 checkout SHA；
- SBT build/test 总结；
- single/dual/small elaboration 结果；
- 无参数默认 GShare 和显式 baseline 兼容结果；
- single/dual/small 全部仿真矩阵；
- 每项测试的命令、exit code、PASS marker、expected/actual；
- 已知限制和未完成项；
- 明确声明没有实现 8/16 核和其他非目标。

只有这些材料齐全，才进入 Codex/用户审核。feature branch 尚未 merge，因此不能描述为主线已经完成。
