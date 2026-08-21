# Diskless Alpine for Flow

本阶段复用 Flow Buildroot 生成的 kernel source 与交叉工具链，把固定版本的 Alpine
riscv64 minirootfs 重新内嵌进独立的 `Image-alpine`。原 Buildroot `Image` 会保留。

从仓库根目录运行：

```bash
BUILDROOT_OUT=build/buildroot-flow
./linux/alpine/build-alpine-image.sh "$BUILDROOT_OUT"
```

脚本下载并校验 Alpine 3.24.1 minirootfs，加入 `linux/alpine/init`，生成 `newc`
initramfs，重建 kernel，然后恢复 Buildroot kernel `.config`。

`Image-alpine` 与 Buildroot `Image` 一样由宿主机在每次仿真开始时装入 LiteDRAM 的
`0x8020_0000`，当前不需要持久化块设备。完整流程和通过门槛见
[`docs/linux/buildroot-alpine.md`](../../docs/linux/buildroot-alpine.md)；已完成与待完成
边界见 [`docs/linux/verification-status.md`](../../docs/linux/verification-status.md)。
