# R01 回报

> 阶段状态：2026-10-05 23:31:33（Asia/Shanghai）执行用户的停止要求。R01 仿真与调度器已终止，正确性 10/10 通过，完整统计 8/10；Q8_0 RVV 512/1024 未完成，独立重跑未运行。本文与 workload profile 只汇总已有数据，W1 未验收完成，W2 未开始。

## 第 0 步：任务文档入库

1. 分支：`feat/rvv-20261005`。提交：`27c12eebb794bd1f9a7d75dbfe0f3ef96fb9cb68`，说明 `Add RVV design notes and R01 task book`，已 push。
2. 本地工作区 `/home/chen/leisure/flow-rvv`，只提交 `docs/rvv-coprocessor-design.md` 和 `docs/tasks/R01-rvv-workload-perfmodel.md`。
3. llama.cpp 修改：无。
4. 结果：设计笔记和 R01 任务书已保存；R01 是执行依据。
5. 未决问题：无。
6. 限制：此步骤没有运行软件或硬件验证。

## 第 1 步：Alan 隔离与工具盘点

1. 分支：`feat/rvv-20261005`。初始文档提交同上；可复现盘点脚本在 `02fe476`。
2. 开始前检查 Alan 的工作区、分支、HEAD、worktree 和运行进程。T01 工作区 `/home/chen/FUN/flow`，当时分支 `t01-b02-baseline-20261005`、HEAD `3adca5e283caa7faa3262c713e9ca7624fa5b2dc`；未切换或修改该工作区。R01 独立 clone 为 `/home/chen/FUN/flow-rvv-r01`，运行文件在 `/home/chen/FUN/flow-r01-runs/20261005-w1`。Alan 有 12 个物理核、20 个逻辑 CPU；R01 正确性检查串行，统计后改为最多 4 个并发单线程进程，全部 `nice -n 10`，小于物理核数的一半。构建使用 `nice -n 10`、`-j 4`。

   实际盘点命令及完整展开结果见上述运行目录的 `inventory.log`、`inventory-final.log`；后者由以下命令产生，`inventory.exit=0`：

   ```bash
   cd /home/chen/FUN/flow-rvv-r01
   bash rvv/tools/inventory.sh
   ```

   | 工具 | 已有版本与位置 | 本次使用 |
   | --- | --- | --- |
   | Linux RISC-V GCC | `/usr/bin/riscv64-linux-gnu-gcc` 13.3.0 | 盘点 |
   | bare-metal GCC | `/usr/bin/riscv64-unknown-elf-gcc` 13.2.0 | 盘点 |
   | Linux RISC-V GCC | `~/Tool/rvtoolchain/bin/riscv64-unknown-linux-gnu-gcc` 13.2.0 | 盘点 |
   | Linux RISC-V GCC/G++ | `~/Tool/RISCV/bin/` 15.1.0 | 主构建与统计 |
   | bare-metal GCC | `~/opt/act4/gcc-2026.07.15/bin/` 16.1.0 | 盘点 |
   | LLVM/Clang | `/usr/bin/clang` 18.1.3 | RVV intrinsics 短片段对照 |
   | QEMU user | `/usr/bin/qemu-riscv64` 8.2.2 | 盘点 |
   | QEMU user | `~/opt/act4/gcc-2026.07.15/bin/qemu-riscv64` 11.0.0 | 全模型与 TCG 插件 |
   | Spike | `~/Tool/RISCV/bin/spike` 1.1.1-dev | 盘点，模型未运行 |
   | pk | `~/Tool/RISCV/riscv64-unknown-linux-gnu/bin/pk` | ELF/sha256 盘点，无版本字符串，未运行 |
   | 构建/处理 | CMake 3.28.3、Ninja 1.11.1、GLib 2.80.0、Python 3.12.3、numpy 1.26.4 | 构建与处理 |

3. llama.cpp 修改：无。
4. 结果：所需工具已存在，未安装工具，也未进行 root 或系统级操作。工具文件哈希在盘点日志中。
5. 未决问题：无。
6. 限制：存在工具不等于完整运行验证；Spike/pk、LLVM 全模型构建均未运行。

## W1：工作负载画像

### 1. 提交与分支

分支 `feat/rvv-20261005`。runner 构建提交 `8c7a34dad4ffe38e97cf772a870d29ea11fe2c5f`；初始统计流水线提交 `ef60d1f55558d1578947be805ffac70a17470abc`；并行调度接续提交 `06f0b362dce028d05787cb28db42c27bc14d0d96`；停止时软件提交 `be761276d6a06a0eadc537a42a70f67422503d62`；只处理已有数据的阶段汇总提交 `8fdd271`，完整 SHA 见 `RUN/partial-summary-source.sha`。`git diff 8c7a34d HEAD -- rvv/runner rvv/tools/build.sh` 为空；`git diff ef60d1f HEAD -- rvv/runner rvv/tools/build.sh rvv/tools/profile.c` 也为空，runner、构建和插件与已验证版本一致。报告与汇总 CSV 的交付提交见本文件的 Git 历史。

### 2. Alan 实际运行、结果与证据

工作区、工具、隔离与调度同第 1 步。以下 `RUN=/home/chen/FUN/flow-r01-runs/20261005-w1`；所有命令从 `/home/chen/FUN/flow-rvv-r01` 执行。表中命令是实际执行的入口，其展开后的 CMake 参数、编译命令或 QEMU 参数完整保留在日志中；`rvv/README.md` 给出新环境从头复现的顺序。没有模型文件或上游源码入 Git。

| 实际命令 | 结果 | 日志/数据（相对 RUN） |
| --- | --- | --- |
| `bash rvv/tools/inventory.sh` | 通过 | `inventory-final.log`、`inventory.exit=0` |
| `python3 rvv/tools/acquire.py "$RUN"`（当时的路径；现为 `rvv/third_party/acquire.py`） | 通过；两模型大小/sha256 与固定官方 revision 一致 | `acquire-final.log`、`models.json`，流水线中再核查见 `pipeline.log` |
| `bash rvv/tools/build.sh "$RUN"` | native、rvv、scalar 三个构建通过 | `build.log`、`build.exit=0`；`build/{native,rvv,scalar}/r01-runner` |
| `bash rvv/tools/probe.sh "$RUN"` | GCC/Clang RVV intrinsics 四种 VLEN 通过，QEMU 运行时 VL/vtype 读取通过 | `probe-fixed.log`、`probe.exit=0` |
| `bash rvv/tools/build-profile.sh "$RUN"` | 两阶段各 3 条向量指令、16 B load/16 B store 与相关 CSR 短片段核查通过 | `pipeline.log`、`profile/smoke-functions.csv`、`profile/smoke-vectors.csv` |
| `python3 rvv/tools/tensors.py "$RUN" q4_0`，之后 `python3 rvv/tools/tensors.py "$RUN"` | 两模型逐张量清单通过 | `tensors-q4_0.log`、`pipeline.log`、`summary/tensors*.csv`、`summary/tensors.json` |
| `python3 rvv/tools/check_tokens.py "$RUN" q4_0` | 四种 VLEN 和标量全部通过 | `correctness-q4_0.log`、`correctness-q4_0.exit=0`、`correctness/*.json/.log` |
| `python3 rvv/tools/check_tokens.py "$RUN" q8_0` | 四种 VLEN 和标量全部通过 | `pipeline.log`、`correctness/comparison.json`（10 个 match 均为 true） |
| `python3 rvv/tools/run-profile.py "$RUN"`，之后 `python3 rvv/tools/run-profile.py "$RUN" --jobs 3 --resume --exclude q4_0-rvv-vlen256` | 8 个 case 完成；其余 2 个按用户要求中止 | `pipeline.log`、`parallel-recovery.log`、`parallel-source.sha`、`profile/{quant}-{mode}-vlen{N}*` |
| `python3 rvv/tools/run-profile.py "$RUN" q4_0-rvv-vlen256 --resume` | 已有数据的 token 和完整性核查通过，没有启动仿真 | `preserved-case-check.log`、`preserved-case-wait-status.txt=0` |
| `python3 rvv/tools/summarize.py "$RUN" "$RUN/summary"`，再生成完整 `reproduce-a/b` | 未运行；完整 10-case 数据未齐 | 原流水线在用户停止前未进入该步 |
| `python3 rvv/tools/run-profile.py "$RUN" q4_0-rvv-vlen128 repeat`，`python3 rvv/tools/compare-repeat.py "$RUN" q4_0-rvv-vlen128` | 未运行；用户要求停止 | 没有独立重跑数据 |
| `python3 rvv/tools/write-profile.py "$RUN" "$RUN/partial-a" --partial`，再生成 `partial-b` 并 `diff -r` | 通过；8 个已完成 case 的 token 再核查、全部汇总 CSV 与 Markdown 两次生成完全一致 | `partial-summary.log`、`partial-summary.exit=0`、`partial-summary-source.sha`、`partial-a/`、`partial-b/` |

完整构建参数见脚本和 `build.log`：主交叉工具链 GCC/G++ 15.1.0，RVV 为 `-march=rv64gcv -mabi=lp64d`，标量 `rv64gc/lp64d`；Release、静态链接、BLAS/OpenMP 关闭，Zfh/Zvfh/Zicbop/Zihintpause 关闭。标量 QEMU 禁用 V；RVV CPU 为 `rv64,v=true,vlen=N,elen=64,vext_spec=v1.0`。全部实际 QEMU 命令由执行脚本逐条打印在日志中，并包含 `nice -n 10`。

早期失败与修复：直接 GitHub/Hugging Face 网络访问及部分代理下载失败，改用本地代理的 SSH 反向转发，模型下载断点续传并最终校验；原始网络日志保留在 RUN。QEMU register API 把结果追加到 GByteArray，初版 probe/plugin 未清空缓冲区，`profile-build.log` 短片段退出 134；修复后每次读取先清空，`profile-build-fixed.log` 及最终流水线短片段通过。失败短片段数据没有作为模型统计使用。未出现 token mismatch。用户建议的本地 Q8 下载和 4 MiB scp 探测完成后，Alan 下载也已完成；两端 SHA 相同，因此没有重复上传大文件；按用户要求删除本地 `/tmp/r01-local-models` 副本。

调度调整：初始逐 case 串行采集开销较大，在任务允许的核数上限内改为最多 4 个并发进程。保留已通过的 Q4 标量和 RVV 128 数据；暂停旧 Python 控制器，但保留当时运行的 RVV 256 QEMU。新控制器最多开 3 个进程，避免覆盖 RVV 256 的原始文件。该进程完成后读取 Linux 保存的实际 wait status，再结束旧控制器，并用 `--resume` 单独验证保留的 case。这是控制器接续，插件和工作负载未改变。操作脚本 `RUN/parallel-recovery.sh`、日志 `parallel-recovery.log`、`preserved-case-wait-status.txt` 保留完整过程；控制器的 143 是主动终止的状态，不能当作模型统计失败或成功状态。23:31 按用户要求终止 R01 的余下 QEMU 和控制器；`RUN/user-stop.txt` 记录停止，随后进程检查已没有 R01 仿真或控制器。被中止的 Q8 RVV 512/1024 不进入汇总。

### 3. llama.cpp 修改清单

无。下载的上游固定在 `e117148a41d8e9bedb72e4c6c3f003ab0fe7f857`，工作区 `git status --short` 为空。独立 `rvv/runner` 仅调用公开 API；阶段边界是 runner 自己的 ELF 函数，未向上游内核插入代码。保留上游默认 repack，不实现新格式或新内核。

### 4. 主要结果

正确性已通过两模型 ×（四种 RVV VLEN + 标量）的 10 个 case，各与同模型的 x86 原生 16-token ID 序列完全一致。提示实际 32 token。阶段边界使 prefill 含第一次采样，decode 是随后 15 次评估，因此 decode 每 token 用 15 作分母。

已有 Q1–Q8 统计表、源码位置、输入哈希和证据路径见 [`docs/rvv-workload-profile.md`](../rvv-workload-profile.md)，小型 CSV 在 `rvv/results/w1/`。Q4 有标量和四种 VLEN 的两阶段数据；Q8 有标量、RVV 128/256 的两阶段数据。Q8 的其他 VLEN 没有有效统计，不能补零或推算为实测。

Q4 在 RVV 128 下解码约 479,886,093 指令/token，标量约 3,243,309,847，指令数量比约 6.76。VLEN 128→1024 的解码总指令下降约 4.06%，但 Q4/Q8 量化 dot 自身的标量、向量、vset 数完全不变，变化主要来自其他函数。这个数量比不是硬件性能加速比。

Q8 在 RVV 128 下解码约 401,935,842 指令/token，标量约 3,817,237,069，数量比约 9.50；RVV 256 约 390,725,519 指令/token，数量比约 9.77。尚无 Q8 RVV 512/1024 的有效数据。

Q4 在 RVV 128 下向量 load 为约 835,933,814 B/解码 token，包含激活等重复读取。用实测 dot 入口块数和 GGUF 格式换算的活跃量化矩阵字节为 345,920,512 B/token，与排除整张嵌入后的量化张量 payload 相等，支持当前路径每 token 逻辑遍历一次这些矩阵；不是 DDR 流量实测。

逐张量清单显示：Q4 文件 169 个 Q4_0、1 个 Q8_0、121 个 F32；Q8 文件 170 个 Q8_0、121 个 F32。两个文件的输出层都是 Q8_0，嵌入与输出层分别存储。

### 5. 未决问题

工作已按用户要求停止。W1 尚缺 Q8 RVV 512/1024 的统计与一个代表 case 的独立仿真重跑；已有表格两次再生成比对通过，但不能代替未运行的独立重跑。未满足完整 W1 验收，不推进 W2。是否恢复余下 W1 工作，等待用户另行指示。

耗时原因：前期远端网络/代理下载缓慢；之后逐条向量寄存器读取和逐元素访存 callback 的插件统计，每个 RVV case 约 10–15 分钟。最初按串行安排并低估插件开销，延后了收尾；后来调整到至多四个低优先级进程。QEMU 的这段运行耗时只用于解释任务进度，不作为性能结论。

### 6. 已知限制

这是固定提示、短生成、单线程、一个 GCC 构建的 QEMU 动态指令画像。函数统计是 exclusive，不能回溯共享 helper 对算子的 inclusive 成本；无法得到的完整算子归属会在 Q5 说明。向量架构访存字节包含激活等重复访问、未包含 scalar load，不能作为 DDR/cache 实测。相关 CSR 仍计 scalar。没有 LLVM 全模型、Spike/pk 模型、Breeze 集成、RTL、FPGA、周期、DDR、PPA 或 token/s 测量；没有 W2 模型估计。
