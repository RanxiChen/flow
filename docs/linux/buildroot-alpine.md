# Buildroot 与无盘 Alpine

本文说明如何从干净源码生成 Flow 四核 Linux 镜像。仓库只保存 Buildroot external
tree 和 Alpine 重打包脚本，不提交第三方源码或大型构建输出。

## 1. 版本边界

当前已验证组合：

| 组件 | 版本/配置 |
| --- | --- |
| Buildroot | 2026.05.1 |
| Linux | 6.18.7 |
| libc | musl |
| OpenSBI | 1.9 generic platform |
| Alpine minirootfs | 3.24.1 riscv64 |
| hart | 4 |
| rootfs | kernel 内嵌 initramfs，无块设备 |

这些值由 `linux/buildroot-external/configs/flow_small_defconfig` 和
`linux/alpine/build-alpine-image.sh` 固定。升级任何一个版本都应新建验证记录，不能
沿用本文件中的旧 hash 宣称通过。

## 2. 干净 Buildroot 工作区

从仓库根目录设置变量。`BUILDROOT_DIR` 指向单独 clone 的官方 Buildroot，输出放在
仓库忽略的 `build/` 下：

```bash
FLOW_ROOT=$(pwd)
BUILDROOT_DIR=../buildroot
BUILDROOT_OUT=build/buildroot-flow
```

Buildroot 必须是全新 clone/checkout。已有 Linux-on-LiteX 工程只可用于参考，不能把
旧 `.config`、patch、output 或 RV32 镜像混进本项目。

```bash
git clone https://gitlab.com/buildroot.org/buildroot.git "$BUILDROOT_DIR"
git -C "$BUILDROOT_DIR" checkout 2026.05.1
```

配置并构建：

```bash
make -C "$BUILDROOT_DIR" \
    O="$FLOW_ROOT/$BUILDROOT_OUT" \
    BR2_EXTERNAL="$FLOW_ROOT/linux/buildroot-external" \
    flow_small_defconfig

make -C "$BUILDROOT_DIR" \
    O="$FLOW_ROOT/$BUILDROOT_OUT" \
    -j"$(nproc)"
```

主要产物：

```text
build/buildroot-flow/images/Image
build/buildroot-flow/images/rootfs.cpio.gz
build/buildroot-flow/images/fw_jump.bin
build/buildroot-flow/images/fw_jump.elf
build/buildroot-flow/images/flow-small.dtb
```

Buildroot 把 `rootfs.cpio` 内嵌到 Linux `Image`。仿真只装载 `Image`，不需要单独
挂载 `rootfs.cpio.gz`。

## 3. Kernel 配置要求

`board/flow/linux.fragment` 至少保证：

- `CONFIG_RISCV_SBI=y`；
- `CONFIG_SMP=y`、`CONFIG_NR_CPUS=4`；
- RVC；
- initramfs/initrd；
- devtmpfs 自动挂载；
- LiteX core、LiteUART及其 console；
- early printk。

Buildroot 最终配置还应保留 RISC-V INTC、PLIC、proc、sysfs 和 tmpfs。构建后应直接
检查最终 kernel `.config`，不能只检查 fragment，因为 Kconfig 依赖可能改变结果。

## 4. OpenSBI 与 DTB

Buildroot 的 OpenSBI 配置生成 `fw_jump`，固定从 `0x8000_0000` 运行，接收
`0x8010_0000` 的 DTB，并跳到 `0x8020_0000`。

设备树源文件位于：

```text
linux/buildroot-external/board/flow/dts/flow/flow-small.dts
```

它必须与 `software/breeze-linux/flow-small.dts` 保持语义一致：四个 CPU、Sv39、
256 MiB RAM、CLINT、PLIC 和 `0x1200_1000` 的 LiteUART。

构建后的 kernel `.config` 必须实际包含：

```text
CONFIG_LITEX=y
CONFIG_LITEX_SOC_CONTROLLER=y
CONFIG_SERIAL_LITEUART=y
CONFIG_SERIAL_LITEUART_CONSOLE=y
```

设备树使用 `compatible = "litex,liteuart"`，启动参数为
`earlycon=liteuart,0x12001000 console=liteuart`，Buildroot getty设备为 `ttyLXU0`。
OpenSBI 1.9源码也必须包含 `litex,liteuart` FDT serial驱动；缺任一项时停止，不进入
长仿真。

## 5. 生成 Alpine Image

Alpine 阶段复用 Buildroot 已生成的交叉工具链和 Linux source，但使用官方 riscv64
minirootfs 替换 initramfs：

```bash
./linux/alpine/build-alpine-image.sh "$BUILDROOT_OUT"
```

脚本会：

1. 下载固定版本的 Alpine minirootfs；
2. 校验固定 SHA-256；
3. 加入 `linux/alpine/init`；
4. 生成 `newc` cpio 并 gzip；
5. 临时修改 kernel `CONFIG_INITRAMFS_SOURCE`；
6. 重建 Linux `Image` 为 `images/Image-alpine`；
7. 恢复原 Buildroot kernel `.config`。

因此 `Image` 与 `Image-alpine` 同时保留，不能让 Alpine 重建覆盖 Buildroot 基线。
脚本会排除 `linux-headers-*`，优先使用 Buildroot host `cpio`，缺失时才回退到系统
`cpio`。

Alpine 当前仍是无盘系统。每次仿真由宿主机重新装载完整 `Image-alpine`，这与
Buildroot initramfs 模式相同。持久化 `/var`、包缓存或用户数据属于后续块设备阶段。

## 6. 先运行 handoff smoke

长 Linux 仿真前先生成探针：

```bash
make -C software/breeze-linux
```

然后使用 `software/breeze-linux/build/handoff-smoke.bin` 代替 kernel。OpenSBI 真正
进入 S-mode payload 后，UART 应出现 `K`。完整仿真命令模板：

```bash
python3 sim/litex/linux_sim.py \
    --opensbi "$BUILDROOT_OUT/images/fw_jump.bin" \
    --kernel software/breeze-linux/build/handoff-smoke.bin \
    --dtb "$BUILDROOT_OUT/images/flow-small.dtb" \
    --bootrom software/breeze-linux/build/bootrom.bin \
    --output-dir build/linux-handoff \
    --elaborate --build --non-interactive \
    --debug-cycles 50000000 \
    --opt-level O3 --jobs "$(nproc)"
```

`debug-cycles` 只是上限，不代表在较小周期数内一定能看到交接。RTL 仿真速度远低于
真实 CPU，OpenSBI 多 hart 初始化可能需要数百万到数千万周期。

Linux 长跑若持续退休但没有 UART，不应只继续扩大 timeout。debug RTL 在精确 trap
点直接打印 `[CORE-TRAP]`，其中包含 hart、interrupt、cause、PC、tval、特权级、trap
target 和当时的 MSIP/MTIP/MEIP/SEIP 输入。它只读取现有核心信号，不增加接口或回压。
每个 hart 最多打印前 256 次，避免 trap storm 淹没日志。修改后必须带 `--elaborate`，
不能复用旧的生成 RTL。

## 7. 运行 Buildroot 和 Alpine

handoff smoke 通过后，把 `--kernel` 分别换成：

```text
build/buildroot-flow/images/Image
build/buildroot-flow/images/Image-alpine
```

建议每种镜像使用独立 `--output-dir` 和日志。通过门槛不是“Verilator 编译成功”，而是：

1. OpenSBI 平台信息出现；
2. Linux earlycon/console 出现；
3. 检测到 4 个 CPU/hart；
4. rootfs/init 成功；
5. Buildroot 登录提示或 Alpine init shell 出现；
6. 无 `LINUX-FATAL`、kernel panic、illegal instruction、page fault storm；
7. 能在用户空间读取 `/proc/cpuinfo` 和 `/proc/meminfo`。

仿真非常慢，推荐把完整 stdout/stderr 写入持久日志并记录 commit SHA、镜像 SHA-256、
命令和退出状态。
