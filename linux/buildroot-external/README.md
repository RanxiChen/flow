# Flow Buildroot external tree

本目录定义 Flow 四核、无盘 Linux 的 Buildroot external tree。rootfs 作为 initramfs
内嵌进 Linux `Image`，不依赖 VirtIO 或块设备。

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

版本、配置要求、Alpine 重打包和仿真门槛见
[`docs/linux/buildroot-alpine.md`](../../docs/linux/buildroot-alpine.md)。当前 Buildroot
镜像已构建成功，但完整 Linux 启动仍是待完成验证，见
[`docs/linux/verification-status.md`](../../docs/linux/verification-status.md)。
