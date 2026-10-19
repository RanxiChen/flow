# Breeze RISC-V 处理器

当前源码的标量/RVV 配置、生成入口和顶层接线见
[源码入口与清理索引](docs/breeze-entrypoints-and-cleanup-audit.md)。下文早期发布版的
五级流水线、Wishbone 与 FASE 描述属于对应历史镜像，阅读当前 RTL 请先看该索引。

Breeze 是用 Chisel 实现的 RV64GC 顺序单发射处理器。当前版本在 KCU105 上以
100 MHz 运行，配备 2 GiB DDR4，已启动 Buildroot Linux，并运行 Python 和 GAPBS。
SoC 使用 LiteX 集成 DDR、串口和 SD 控制器，FASE 提供独立的 JTAG 调试入口。

本文介绍已上板验证的单核配置。仓库也保留多核配置，本文的性能数据均来自单核。
另有 `single-zve32f` 的 100 MHz 原 RTL 实现：已生成通过时序门槛的 KCU105
bitstream，尚未完成上板验证。其构建策略见下文，不能把已有单核的上板结果算作
Zve32f 的上板结果。

## 下载与启动

[2026-09-18 发布页](https://github.com/RanxiChen/flow/releases/tag/breeze-kcu105-20260918)
提供配套的 FPGA 与 Linux 镜像，可直接用于 KCU105：

| 文件 | 内容 |
| --- | --- |
| [FPGA 镜像](https://github.com/RanxiChen/flow/releases/download/breeze-kcu105-20260918/breeze-kcu105-100mhz-sd-fase-20260918.zip) | 单核 100 MHz，SD、FASE、硬件记录器；不含 ILA |
| [Linux 启动包](https://github.com/RanxiChen/flow/releases/download/breeze-kcu105-20260918/breeze-linux-gapbs-python-20260918.zip) | Linux、OpenSBI、设备树、启动跳板和 boot.json，包含 GAPBS 与 Python |
| [SHA256SUMS](https://github.com/RanxiChen/flow/releases/download/breeze-kcu105-20260918/SHA256SUMS) | 两个 ZIP 的 SHA-256 校验值 |

FPGA 产物来自 `29ee514`，Linux 配置来自 `b2a895b`，完整提交号记在发布说明中。
镜像保存在 GitHub Release，源码仓库不包含这些大文件。

1. 用 Vivado Hardware Manager 通过 JTAG 加载 FPGA 包中的 `xilinx_kcu105.bit`。
2. 将 Linux 包中 `boot/` 下的全部文件复制到 SD 卡 FAT 分区根目录。
3. 插入 SD 卡，以 115200 波特率连接 FPGA 串口，在 LiteX BIOS 执行：

```text
sdcard_init
sdcardboot
```

BIOS 从 SD 卡将文件装入 DDR，随后经启动跳板、OpenSBI 进入 Linux。登录用户为
`root`，此镜像未设置密码。进入 shell 后可以检查 Python 和 GAPBS：

```sh
python3 --version
gapbs-smoke
```

已验证的 Python 版本为 3.14.6。`gapbs-smoke` 会运行六项小图测试，查看每项输出中的
`Verification: PASS`。

Linux 使用内嵌 initramfs，运行期间的文件写入保存在 RAM，断电后丢失。
这一版由 BIOS 使用 SD 卡加载系统；Linux 挂载 SD 根分区仍在调试，网络尚未验证。

## 微架构

流水线分为 IF、ID、EXE、MEM、WB。普通整数运算在 EXE 完成；乘除法、浮点和数据缓存
访问由 MEM 发起请求，并等待结果返回。MEM 等待期间会阻塞流水线，因此这些单元的
多周期操作会影响整核吞吐。

![Breeze 阻塞式五级流水线](docs/figures/breeze-core.svg)

下图列出前端、执行单元、缓存及特权架构模块。参数对应 `single / gshare / linux` 配置。

![Breeze 核心组织与参数](docs/figures/breeze-organization.svg)

| 项目 | 配置 |
| --- | --- |
| 指令集 | RV64IMAFDC、Zicsr、Zifencei |
| 执行方式 | 单发射、顺序执行，阻塞式 MEM |
| 分支预测 | GShare，8 位全局历史，256 项 2 位 PHT；16 项全相联 BTB |
| 取指缓冲 | 6 项 FIFO，支持 16/32 位指令重对齐 |
| L1 I-cache / D-cache | 各 8 KiB，4 路，64 组，32 B 缓存行 |
| L2 / coherence home | 16 KiB，8 路，64 组，32 B 缓存行，单 bank |
| 虚拟内存 | Sv39，I-TLB / D-TLB 各 16 项，硬件页表遍历、SFENCE.VMA |
| 特权架构 | M/S/U，异常与中断委托，8 个有效 PMP 项，Sstc |
| 调试 | FASE：JTAG halt、寄存器访问、指令注入；硬件记录器保存近期执行现场 |

缓存容量不含标签和一致性元数据。实现入口见
[核心配置](design/src/main/scala/config/config.scala)、
[核心连接](design/src/main/scala/core/BreezeCore.scala)和
[MMU](design/src/main/scala/mmu/BreezeMmu.scala)。

## SoC 与存储结构

L1 指令缓存和数据缓存接入 L2/Home，SD DMA 也作为 client 通过该入口访问内存。
L2 的内存请求和核心的非缓存 MMIO 请求进入 LiteX 64 位 Wishbone 总线；外设寄存器
使用 32 位 CSR 接口。LiteDRAM 管理板上的 2 GiB DDR4。

![Breeze KCU105 SoC 与存储层次](docs/figures/breeze-soc-memory.svg)

三张图的可编辑 draw.io 文件与 SVG 一同保存在 [docs/figures](docs/figures)。

启动包使用以下加载地址。`boot.json` 的 `addr` 指定执行入口，即启动跳板地址。

| 文件 | 加载地址 |
| --- | --- |
| `fw_jump.bin` | `0x8000_0000` |
| `serial-handoff.bin` | `0x8008_0000` |
| `flow-kcu105-tiny.dtb` | `0x8010_0000` |
| `Image` | `0x8020_0000` |

## 从源码构建

以下命令在仓库根目录执行。FPGA 构建需要 Vivado、Java/sbt、RISC-V 交叉工具链，
以及包含 Migen、LiteX、LiteX-Boards、LiteDRAM、LiteSDCard 的 Python 环境。
请使用适配本项目的 LiteX 环境；仅安装任意版本的上游依赖不能保证复现已发布产物。

### FPGA

```sh
python3 fpga/kcu105/target.py \
    --cpu-type breeze-tiny \
    --sys-clk-freq 100000000 \
    --with-sdcard --with-fase \
    --output-dir build/fpga/kcu105-sd-fase-100mhz \
    --build
```

目标脚本会先通过 sbt 生成 RTL，再构建 LiteX BIOS 并运行 Vivado。
产物为 `build/fpga/kcu105-sd-fase-100mhz/gateware/xilinx_kcu105.bit`。
上述命令启用 FASE 与硬件记录器，不启用 ILA。重新构建后需检查实现时序报告再上板。

### `single-zve32f` 原 RTL 的 100 MHz FPGA 构建

对外展示版本使用原 RTL `215f18f9a3e0bf32ccd1d00e1937aa32fbce9dd0`，
Window 资源实验保存在独立分支，不属于本构建。CVFPU 子模块提交
`d0ed6e548bfd298a03f2f8c62e610a5fd1afa91e` 可从 fork 的
`flow-zve32f-r07c` 分支取得。以下在 Alan 的 Vivado 2022.2 和项目 `flow`
环境中执行；每次使用新的 `R07C_RUN` 目录。先准备子模块，并使可用的 sbt
位于 `PATH`（Alan 的 Coursier 旧入口可能指向已不存在的安装）：

```sh
git submodule update --init third_party/cvfpu
source /home/chen/miniforge3/bin/activate flow
export PATH="/home/chen/.sdkman/candidates/sbt/current/bin:$PATH"
FLOW_ROOT="$PWD"
R07C_RUN="/home/chen/FUN/flow-runs/zve32f-r07c-rebuild"
VIVADO="/home/chen/Tool/FPGA/Vivado/2022.2/bin/vivado"
mkdir -p "$R07C_RUN"

python3 fpga/kcu105/target.py --cpu-type breeze-zve32f \
    --with-sdcard --sys-clk-freq 100000000 --vivado-threads 4 \
    --output-dir "$R07C_RUN/kcu105"
GATEWARE="$R07C_RUN/kcu105/gateware"
python3 records/zve32f-r07c-20261013/make_synth.py "$GATEWARE"
cd "$GATEWARE"
"$VIVADO" -mode batch -source r07c-synth-only.tcl
```

`target.py` 生成 `single-zve32f` RTL、LiteX BIOS 和生产 Vivado 工程；
`make_synth.py` 取生产 Tcl 中相同的综合/XDC 前缀，在布局前停下并输出
`r07c-synth.dcp`、资源和综合时序报告。不要用 `target.py --build` 的默认实现
结果代替下面的策略。保持 `FLOW_ROOT`、`R07C_RUN`、`GATEWARE`、`VIVADO`
四个变量在同一 shell 中，继续从同一个综合 DCP 分别运行默认和 Explore：

```sh
mkdir -p "$R07C_RUN/route-default" "$R07C_RUN/route-explore"
cd "$R07C_RUN/route-default"
"$VIVADO" -mode batch \
    -source "$FLOW_ROOT/records/zve32f-r07c-20261013/route_impl.tcl" \
    -tclargs "$GATEWARE" default
cd "$R07C_RUN/route-explore"
"$VIVADO" -mode batch \
    -source "$FLOW_ROOT/records/zve32f-r07c-20261013/route_impl.tcl" \
    -tclargs "$GATEWARE" explore
```

默认策略为 `opt_design Default → place_design Default → route_design Default →
post-route phys_opt_design Default`；Explore 为 `opt_design Default →
place_design Explore → pre-route phys_opt_design AggressiveExplore →
route_design Explore → post-route phys_opt_design Default`。已归档的原 RTL
运行分别得到 WNS −0.471 ns 和 −0.001 ns，均不能直接生成 bitstream。
这次可烧录文件使用 Explore 的已布线 DCP，再执行一次后布线优化与重布线：

```sh
mkdir -p "$R07C_RUN/postopt" "$R07C_RUN/bitstream"
cd "$R07C_RUN/postopt"
"$VIVADO" -mode batch \
    -source "$FLOW_ROOT/records/zve32f-r07c-20261013/route_postopt.tcl" \
    -tclargs "$R07C_RUN/route-explore/routed.dcp" "$GATEWARE"
cd "$R07C_RUN/bitstream"
ln -s "$R07C_RUN/postopt/postopt-routed.dcp" routed.dcp
"$VIVADO" -mode batch \
    -source "$FLOW_ROOT/records/zve32f-r07c-20261013/make_bitstream.tcl" \
    -tclargs "$GATEWARE"
sha256sum xilinx_kcu105.bit
```

后布线脚本依次运行 `phys_opt_design -directive AggressiveExplore` 和
`route_design -directive NoTimingRelaxation`。先查看 `postopt/after-timing.rpt`
的 WNS/TNS、hold 违例数及 `postopt/route-status.rpt` 的路由错误数；bitstream
脚本也会重新检查完整时序、
实际最差 setup/hold slack，并在门槛失败时拒绝写出。归档运行的最终结果为
WNS 0.000 ns、TNS 0、零 setup/hold 违例、零路由错误；bitstream SHA256 为
`9b38ab07e6bb0ef0e3661a69db919b2d1a4619fedb2b4ec2f0440b686ef0f1aa`，
在 Alan 的 `/home/chen/FUN/flow-runs/zve32f-r07c-postopt-20261013/bitstream/`
下。余量接近零，新的运行必须以自己的路由和门控报告为准；本产物没有 ILA，
也尚未证明板级启动。命令、DCP SHA 和报告见
[R07c 记录](records/zve32f-r07c-20261013/result.md)。

### Linux、GAPBS 与 Python

已发布镜像使用 Buildroot 2026.05.1、musl 和 GCC 14.4.0。准备 Buildroot 源码目录后运行：

```sh
BUILDROOT_DIR=/path/to/buildroot bash linux/sd-gapbs/build.sh
```

脚本使用 `flow_tiny_gapbs_defconfig`，编译 Linux 6.18.7、OpenSBI、GAPBS 和 Python，
将根文件系统嵌入 Linux Image，并生成启动跳板与 `boot.json`。

- `build/sd-gapbs/output/`：Buildroot 构建目录及交叉工具链。
- `build/sd-gapbs/boot/`：可复制到 SD 卡 FAT 分区的启动文件及校验值。

构建脚本不写入 SD 卡。它与 Linux SD 根分区配置使用不同输出目录，避免混用镜像。
配置入口为 [Buildroot defconfig](linux/buildroot-external/configs/flow_tiny_gapbs_defconfig)
和[构建脚本](linux/sd-gapbs/build.sh)。

### RTL 测试

```sh
cd design
sbt test
```

仿真环境和定向测试见 [LiteX 仿真文档](sim/litex/README.md)。

## GAPBS 性能

测试平台为 KCU105 单核 Breeze，100 MHz、2 GiB DDR4，使用上述 Buildroot musl 镜像。
GAPBS 源码固定为 `b5e3e19c2845f22fb338f4a4bc4b1ccee861d026`，启用 OpenMP 构建。

| 算法 | 命令 | 顶点数 | 无向边数（程序报告） | Average Time |
| --- | --- | ---: | ---: | ---: |
| BFS | `./bfs -g 18 -n 50` | 262143 | 3805449 | 3.03940 s |
| BC | `./bc -g 18 -n 50` | 262143 | 3805449 | 42.47229 s |

Average Time 是 50 次算法运行的平均耗时，不包含生成图和构图。
两次大图运行未使用 `-v`；正确性检查来自此前六项 `-g 10 -n 1 -v` 小图测试，均为 PASS。
原始串口记录未包含 OpenMP 环境变量，实际线程设置未独立确认。
完整试次与计时记录见 [测试数据](docs/benchmarks/breeze-gapbs-20260918.md)。

后续测量可显式固定线程设置，并保存控制台输出：

```sh
cd /opt/gapbs
export OMP_NUM_THREADS=1
export OMP_DYNAMIC=FALSE
time ./bfs -g 18 -n 50
time ./bc -g 18 -n 50
```
