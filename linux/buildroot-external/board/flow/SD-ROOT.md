# Breeze KCU105：Buildroot SD 根文件系统适配

本配置配合 `29ee514` 的 SD＋FASE FPGA CSR 接口。它是待上板验证的适配，
不能把设备树编译或驱动编译成功记成 Linux SD 启动成功。

## 四个文件分别负责什么

1. `configs/flow_tiny_sd_defconfig`：Buildroot 的构建配方。沿用 Linux 6.18.7、
   musl、OpenSBI 1.9，改为生成 256 MiB ext4 根文件系统，不内嵌 initramfs。
2. `linux-sd.fragment`：Linux 内核选项。MMC 框架、块设备、LiteX 主机驱动、
   ext4 都是 `=y`，保证挂载根分区之前即可使用。
3. `dts/flow/flow-kcu105-tiny-sd.dts`：描述实际硬件；声明 PLIC 中断源 11、
   100 MHz 参考时钟、SD 电源和五段寄存器；指定第二分区作为根目录。
4. `patches/linux/6.18.7/0001-mmc-litex-breeze-sd-profile.patch`：内核驱动适配。
   通过 `BR2_GLOBAL_PATCH_DIR` 在 Buildroot 解包内核后应用，可在全新目录复现。

旧 `flow_tiny_defconfig` 和旧 DTB 不变，仍用于已经验证过的无盘基线。
新配置须使用独立 Buildroot 输出目录；不能将 SD DTB 交给不含 SD 的 bitstream。

## 为什么不照抄 Rocket 的设备树

寄存器资源必须来自这次 FPGA 的 `csr.json`，而不是其他板卡的例子：

| 名称 | 地址 | 长度 |
|---|---|---|
| phy | 0x12006000 | 0x1c |
| core | 0x1200601c | 0x2c |
| reader | 0x12006048 | 0x20 |
| writer | 0x12006068 | 0x20 |
| irq | 0x12006088 | 0x0c |

这是同一页里的五个紧密排列的区域，不能机械地把每段长度都写成 0x100。
`sdcard_interrupt=2` 是 LiteX 内部编号，实际连到 Breeze PLIC 的是 11。
DMA 地址寄存器虽是 64 位，平台实际总线只有 32 位地址，因此驱动限制 DMA mask 为 32 位。
DDR 位于 0x80000000–0xffffffff，处于该范围内。

## 新驱动路径

使用独立 `flow,breeze-litex-mmc-v1` 匹配，不将其伪装成未经适配的旧接口。

- 上电阶段设置 PHY 单线模式，产生初始时钟；参考 100 MHz / 256，最低约 390.625 kHz。
- 由 Linux MMC 框架正常执行 ACMD6，驱动在 set_ios 回调中同步切换 PHY 位宽。
  不再沿用旧 LiteX 驱动在第一次数据命令前强制插入 ACMD6 的逻辑。
- 工作频率上限先设为 5 MHz；当前分频代码向安全方向取整，实际可能更低。
- 当前事件 bit 3 是 data_done，bit 4 是 cmd_done。新路径使用 bit 4，
  随后仍检查命令、数据和 DMA 的完成状态；等待中断有一秒超时。
- 新路径每次使用页对齐的 coherent 中转缓冲区，通过 memcpy/SG copy 搬进搬出。
  因此无须假定所有 MMC 请求自带 8 字节对齐的地址。优先正确性，暂不优化零拷贝。
- `dma-coherent` 的前提是 FPGA 的 SD DMA 接入 Breeze Home 一致性入口。
  它不是一种软件修复开关，若换成旁路 DDR 的 DMA 就不能沿用这个声明。
- 当前未接卡检测引脚，配置为启动前插卡、不支持在线拔卡的固定介质。

## 构建入口

```sh
make -C "$BUILDROOT_DIR" O="$BUILDROOT_OUT" \
  BR2_EXTERNAL="$FLOW_ROOT/linux/buildroot-external" flow_tiny_sd_defconfig
make -C "$BUILDROOT_DIR" O="$BUILDROOT_OUT" -j8
```

这些变量必须指向实际目录；输出目录须独立于旧无盘构建。
Buildroot ext2 系列镜像选中 ext4 后，产物文件名仍可能是 `rootfs.ext2`，
应以镜像实际文件系统格式核验，不仅凭后缀判断。

## 尚待完成的验收

设备树/驱动编译 → 内核识别卡与分区 → 只读核验扇区 → 文件读写与校验 →
将 ext4 放入 SD 第二分区 → 根文件系统启动 → 正常关机重启后的持久化验证。
制卡前必须备份现有卡内容并核对目标磁盘；本配置本身不会写卡。
KCU105 系统控制器切换 SD 归属的具体已验证操作保存在项目本地排除文档中。
