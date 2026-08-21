# Linux bring-up 验证状态

本文只记录已经执行过的证据及其边界。构建成功、结构检查、固件执行和 Linux 启动是
四个不同门槛，不能互相替代。

## 已通过

### CPU/RTL

- 完整 Chisel 回归：43 suite、181 tests，全部通过；
- Linux PMA 定向测试：DDR、UART 和非法区域分类通过；
- DCache 定向测试：12 tests 通过；
- 精确 EBREAK decode 与 breakpoint trap 测试通过；
- 四核 Linux debug RTL 和关闭 tandem 的 production RTL 均成功 elaboration。

### SoC

- 四个 hart 都能从 Linux reset ROM `0x1001_0000` 启动；
- ROM UART 探针能通过共享 Wishbone/PMA 向 `0x1300_0000` 写字符；
- LiteDRAM DDR3 模型按 256 MiB 建立，OpenSBI/DTB/payload 能完成范围与重叠检查；
- CLINT、PLIC 和 16550 都进入 Linux SoC 地址图；
- PLIC source 10 只连接 Linux 16550 UART，没有 LiteX internal IRQ alias。

### 软件构建

在一次干净的 Buildroot 2026.05.1 构建中，已生成：

| Artifact | 大小 | SHA-256 |
| --- | ---: | --- |
| Buildroot `Image` | 约 33 MiB | `e2b609cc47ae5fdf0970062a7f01629a96b2640b97e27ce283b94b3e805c66c4` |
| `rootfs.cpio.gz` | 约 6.8 MiB | `4e928179dec3f2127d46c7d2254566b1b0a52815dab58914669fa6acf99ae7fe` |
| OpenSBI `fw_jump.bin` | 约 272 KiB | `fa96fc4b7b7f56c2110f713c3268db62eb90951f0fec0f56e81b811007fefd41` |
| `flow-small.dtb` | 2434 B | `f876002d20f7ce146d02def3bbb9e174ce2a464e7163ea047d85b7d71c440c22` |
| Alpine `Image-alpine` | 约 29 MiB | `adcedf0c3a86d48e6f6fad6e10ce124eaa158b25029acfa2ca2bd30bfae08e38` |

这些 hash 证明的是该次构建产物身份；版本、工具链或时间戳变化后，重建 hash 可以不同。

### OpenSBI 执行

- 四个 hart 均从 ROM 跳到 `0x8000_0000` 并持续退休 OpenSBI 指令；
- 2000 万周期运行中每 hart 退休约 229 万到 237 万条指令；
- 没有 `LINUX-FATAL`；
- 符号解析显示 boot hart 在 libfdt 初始化，其他 hart 经过
  `_wait_for_boot_hart`、HSM wait 和 atomic 状态路径。

## 尚未通过

- 正确 handoff smoke 在已运行的 600 万周期窗口内尚未输出 `K`；该窗口不足以宣布
  失败，也不能宣布交接成功；
- OpenSBI UART banner 尚未取得；
- Buildroot Linux kernel 尚未取得 earlycon、SMP 和用户空间启动日志；
- Alpine 尚未取得 `/init` 或 shell 日志；
- 尚未运行真实块设备、网络或 FPGA 板级验证。

因此当前项目状态是：**硬件平台与镜像构建已实现，长时间 Linux 运行验证待完成**。

## 已纠正的误区

早期诊断曾把 `software/breeze-linux/build/bootrom.bin` 同时作为 kernel 占位文件。
这不是合法 handoff payload：OpenSBI 跳到 `0x8020_0000` 后会执行其中“跳回
`0x8000_0000`”的 reset 逻辑，造成 OpenSBI 重入。由此产生的 2000 万周期运行只可
用于证明四核和 OpenSBI 持续执行，不能证明 S-mode handoff。

仓库已经加入正确的 `handoff-smoke.bin`：入口链接到 `0x8020_0000`，向 16550 写
`K` 后停在 WFI。后续应以 `K` 为 OpenSBI 交接门槛。

## 下一次验证顺序

1. 在目标 commit 上运行完整 `sbt test`；
2. 构建 `software/breeze-linux`；
3. 用足够长 watchdog 运行 handoff smoke，保存 `K`、UART MMIO 和 per-hart progress；
4. handoff 通过后运行 Buildroot `Image`；
5. Buildroot 用户空间通过后运行 `Image-alpine`；
6. 每一步单独保存命令、commit SHA、镜像 SHA、退出码和 fatal/panic 扫描；
7. 在仿真完全闭环前不进入 FPGA 上板或持久化存储实现。
