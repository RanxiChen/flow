当前构建配置已收敛为纯标量 `four` / `single` 和 `single-zve32f`，固定 GShare/Linux。
`build_sd.sh`、`prepare_sd_boot.py`、`linux/sd-gapbs/build.sh` 和 `collect_sd_products.py`
均不再接受 `single-rvv`。收集器的向量参数现为 `--single-zve32f`、
`--single-zve32f-software` 和可选 `--zve-source-sha`。

以下保留早期三产品交付记录，其中 `single-rvv` 命令只适用于对应历史版本。

# BE2 KCU105 三产品 SD 启动

2026-10-11。交付范围是 JTAG 加载的 FPGA bitstream、其中初始化的 BIOS、FAT SD 软件文件。SD 只存 Linux/OpenSBI/DTB/软件镜像；不存或加载 bitstream，不追加 Flash 配置镜像。

| 产品 | `build_sd.sh` profile | CPU 参数 | 核数 | 缓存和内存边界 |
|---|---|---|---:|---|
| 四核基础版 | `four` | `breeze` | 4 | 每核 L1I/L1D 各 4KiB、两路；L2 四路/总128KiB；64-bit AXI |
| 普通单核 | `single` | `breeze-tiny` | 1 | 从同一 `small` 配置仅改变核数；L2 总32KiB，其余每核参数相同 |
| 单核 RVV | `single-rvv` | `breeze-rvv` | 1 | 同一标量基础、写穿 L1D、dataless Home、512-bit AXI；R04 向量后端 |

三者均为 100MHz、RV64IMAFDC、GShare、Sv39、32-byte line、一个标量 MSHR、两个标量 miss slot、32-bit PA、2GiB DDR、64KiB BIOS ROM/SRAM。RVV 默认 VLEN=DLEN=512、八个64-bit lane、VIQ32、16KiB返回缓冲；仅支持已实现的整数与 unit-stride 子集。未实现的指令和 R05 临时 Serial 条件精确陷入，不宣称完整 V ISA。

SD 使用官方 LiteSDCard/LiteX read+write IP。64-bit Wishbone DMA 经专用一致性桥进入 Home，不直接旁路 DDR。基础版读最新脏数据、写前失效 L1D；RVV 版写穿标量与 SD/RVV 失效及 AXI 写完成排序。错误返回 ERR，不伪装 ACK。BIOS 使用固定上游 hash 匹配的局部驱动补丁，400kHz 初始化、最高5MHz四线传输、对齐 bounce buffer、内存屏障、有限重试和 DMA 错误检查。

CSR SD 页为 `0x12006000`，IRQ11，UART IRQ10；DTB 和 Linux MMC 驱动使用真实生成的 PHY/core/reader/writer/IRQ 子块偏移。软件打包器检查这些偏移和 hart 数，记录 BIOS/CSR/镜像哈希。

## 已生成的三个产品

2026-10-12 交付目录为本工作区 `build/be2-products/be2-release-three-sd-20261012/`。三个子目录 `four`、`single`、`single-rvv` 各有 `be2-<profile>-sd.bit`、内置 BIOS 的独立核对文件、配置/时序报告及对应 `sd/` 五文件启动包，根目录 `manifest.json` 记录准确源与全部文件 SHA256。

三者100MHz最终 routed setup/hold/pulse-or-skew 全部通过，零路由错误；四核 WNS +0.002ns，普通单核 +0.127ns，RVV +0.012ns。已经生成 bitstream，尚未上板验证新 Linux/SD 启动，外部 SD/card/mux 时序仍 OPEN。原 BE2 P1/S2 未过项保留，详见报告。

## 构建

仿真、编译及 RTL 生成前重新读取 [共享主机配置](../../docs/cross-project/simulation-host.md)，先预检 cloud_chen，不可用才使用 Alan；Vivado 始终用 Alan。通过本地反向 HTTP 代理同步准确提交，不使用 bundle。主机选择和资源上限由运行者落实，脚本不在本地自动发起远端任务。

在已预检的环境中，从准确提交生成独立工程；每个输出目录必须不存在：

```sh
bash fpga/kcu105/build_sd.sh four /absolute/fresh/four
bash fpga/kcu105/build_sd.sh single /absolute/fresh/single
bash fpga/kcu105/build_sd.sh single-rvv /absolute/fresh/single-rvv
```

可加 `--prepare-only` 仅生成 RTL/BIOS/工程；随后 `--vivado-only` 消费已冻结工程。构建器冻结源、记录约束、先检查映射再实现；完整 setup/hold/pulse-or-skew 指标不通过时在 write_bitstream 前拒绝生成。最小功能门禁由 BE2 执行记录单独绑定，不把只成功生成工程称为测试通过。产物为 `gateware/xilinx_kcu105.bit`，BIOS 已初始化在其中；独立 BIOS 文件为 `software/bios/bios.bin`。

完整软件可以稍后独立编译：

```sh
BUILDROOT_DIR=/path/to/buildroot BUILDROOT_OUT=/fresh/software/output \
  SD_GAPBS_BOOT=/fresh/software/boot SD_GAPBS_PROFILE=four JOBS=2 \
  bash linux/sd-gapbs/build.sh
```

profile 另可选 `single`、`single-rvv`。三个 DTB 分别为 `flow-be2-quad`、`flow-be2-single`、`flow-be2-single-rvv`。同一 SMP4/SD kernel 可配三份 DTB；软件 ABI 不改变时更新 SD 文件即可。已有 GAPBS SDK 可用 `linux/sd-gapbs/rebuild-kernel.sh` 在新目录重编 Linux，不修改旧 SDK/rootfs。完整构建配方仍是 `build.sh`。

按硬件工程打包匹配的软件：

```sh
python3 fpga/kcu105/prepare_sd_boot.py --profile four \
  --hardware-dir /absolute/fresh/four --images /software/output/images \
  --output /fresh/fat-files
```

将 `Image`、`fw_jump.bin`、匹配 `.dtb`、`serial-handoff.bin`、`boot.json` 放在 FAT 分区根目录。用户加载 `.bit` 后，BIOS 在 DDR training 后自动尝试 SD 软件启动；hart0 初始化，辅助 hart 等待发布入口，然后每核保留自己的 hart ID 进入 OpenSBI。Linux 使用内嵌 initramfs；启动后可执行既有 `gapbs-smoke`，不要求 SD 根文件系统。镜像加载地址见生成的 `boot.json`。

三个硬件工程完成后，先用对应的最终硬件目录重新打包各自 FAT 文件，再运行 `collect_sd_products.py` 收集交付目录。参数是 `--four`、`--single`、`--single-rvv` 三个硬件目录，相应的三个 `--*-software` 目录，以及准确的 `--source-sha` 和不存在的 `--output`。收集器要求实际 bitstream、非负 setup/hold/pulse 余量、零路由错误、匹配 BIOS/CSR/bitstream 哈希，并比较三份配置；不接受旧硬件绑定的软件包。交付目录按产品包含 `.bit`、`bios.bin`、CSR、配置、时序和 SD 路径报告、`sd/` 启动文件以及逐文件 SHA256。

若 RVV 在标量配置冻结后修复独有 RTL，可额外给 `--rvv-source-sha`；RVV 的 build-binding 必须记录与 `--source-sha` 相同的 `scalar_base_source_sha`。当前 d902fb5 对 7f7f186 的生产 RTL/config 差异仅 RvvLoadStore、RvvAxiArbiter、RvvRegisterFile、RvvAluPipeline 四个 RVV 文件，生成时另检查准确差异清单及 immutable snapshot hashes，普通单/四核的证据仍绑定 7f7f186。收集器先检查全部报告和完整五文件 SD manifest，再创建交付目录。

## 上板后的最小使用检查与软件更新

串口为115200 baud。加载对应 `.bit` 前插入准备好的SD卡；FAT根目录的五个文件必须来自同一个产品的 `sd/`，避免混用DTB。BIOS内置于bitstream，独立 `bios.bin` 供核对和后续构建。硬件接口兼容时，可独立重编软件并替换这些SD文件。

Linux控制台先核对 `cat /sys/devices/system/cpu/online`：四核应为 `0-3`，两种单核为 `0`；再核对SD/MMC枚举及串口加载日志。新板上结果尚未执行，不能将下面的使用命令当作通过证据。

```sh
gapbs-smoke
# 四核额外运行同一小图与verification；既有smoke保持单线程基线。
cd /opt/gapbs
OMP_NUM_THREADS=4 OMP_DYNAMIC=FALSE ./bfs -g 10 -n 1 -v
```

普通应用及GAPBS使用标量RV64IMAFDC工具链选项。RVV版只提供R04已实现整数/unit-stride子集；当前Buildroot应用保持标量编译，不启用完整V ISA自动向量化。需要向量加速的程序应使用已实现的指令及对应定向样例，未实现的编码会精确陷入非法指令。Linux启用了用户向量上下文保存/恢复，关闭内核抢占式向量路径，并将向量usercopy阈值设为INT_MAX；这不构成完整V扩展兼容声明。

## 证据范围

最新构建、时序及测试状态以 [BE2 报告](../../docs/tasks/BE2-report.md) 和逐工程 manifest 为准，历史29ee514/b2a895b板上 GAPBS 成功不能替代新产品的板上证明。未上板时，报告必须保留 `BOARD_NOT_RUN`。

[SD timing](sd-timing.md) 的定向内部预算不构成完整 SD/card/mux 外部时序证明。加载后仍需核验 KCU105 SD mux、初始化/文件 CRC、Linux MMC 枚举，以及四 hart 在线数。构建脚本不烧板、不写 SD 卡。
