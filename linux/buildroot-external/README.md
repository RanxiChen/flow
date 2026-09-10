# Flow Buildroot external tree

本目录定义 Flow 四核、无盘 Linux 的 Buildroot external tree。rootfs 作为 initramfs
内嵌进 Linux `Image`，不依赖 VirtIO 或块设备。

KCU105 单核 `breeze-tiny` 使用 `flow_tiny_defconfig`，四核仿真继续使用
`flow_small_defconfig`。单核仍保留完整集群/L2/MESI、PLIC 和 CLINT。

从仓库根目录、使用干净的 Buildroot 2026.05.1 checkout：

```bash
FLOW_ROOT=$(pwd)
BUILDROOT_DIR=../buildroot
BUILDROOT_OUT=build/buildroot-flow

make -C "$BUILDROOT_DIR" \
    O="$FLOW_ROOT/$BUILDROOT_OUT" \
    BR2_EXTERNAL="$FLOW_ROOT/linux/buildroot-external" \
    flow_small_defconfig

make -C "$BUILDROOT_DIR" \
    O="$FLOW_ROOT/$BUILDROOT_OUT" \
    -j"$(nproc)"
```

仿真使用：

```text
images/fw_jump.bin   -> 0x80000000
images/flow-small.dtb -> 0x80100000
images/Image          -> 0x80200000
```

KCU105 单核构建使用独立输出目录：

```bash
FLOW_ROOT=$(pwd)
BUILDROOT_DIR=../buildroot
BUILDROOT_OUT=build/buildroot-flow-tiny
make -C "$BUILDROOT_DIR" \
    O="$FLOW_ROOT/$BUILDROOT_OUT" \
    BR2_EXTERNAL="$FLOW_ROOT/linux/buildroot-external" \
    flow_tiny_defconfig
make -C "$BUILDROOT_DIR" O="$FLOW_ROOT/$BUILDROOT_OUT" -j"$(nproc)"
```

其设备树为 `flow-kcu105-tiny.dtb`，仅声明 hart 0、对应的 CLINT 中断和
两个 PLIC M/S context；内存为 KCU105 暴露的 `0x80000000` 起始 1 GiB。
加载地址仍为 `fw_jump.bin` → `0x80000000`、设备树 → `0x80100000`、
`Image` → `0x80200000`。不能沿用四核仿真的 `flow-small.dtb`。
新增配置不代表已经通过 FPGA Linux 启动验证；先完成 DDR 训练和内存测试。

版本、配置要求、Alpine 重打包和仿真门槛见
[`docs/linux/buildroot-alpine.md`](../../docs/linux/buildroot-alpine.md)。当前 Buildroot
镜像已构建成功，但完整 Linux 启动仍是待完成验证，见
[`docs/linux/verification-status.md`](../../docs/linux/verification-status.md)。
