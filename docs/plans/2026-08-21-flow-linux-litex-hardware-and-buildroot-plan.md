# Flow 四核 Linux-on-LiteX 硬件与 Buildroot 迁移计划

**日期：** 2026-08-21  
**状态：** 计划冻结；尚未修改 RTL，尚未 clone Buildroot  
**当前基线：** `feat/multicore-1-2-4` / `e5e1f1e10828075597c18d1cfa3685a18704d8f5`  
**第一目标：** LiteX 仿真中由四核 Flow 启动 Buildroot，并确认 Linux 识别 4 个 CPU 进入 shell  
**第二目标：** 在相同 SoC 结构上接入 KCU105 DDR4 与真实存储，启动 Alpine Linux

具体硬件需求、理由和Buildroot/Alpine差异见：
`docs/plans/2026-08-21-buildroot-alpine-hardware-requirements.md`。

## 1. 总体原则

1. LiteX 是 SoC 集成框架；Flow Cluster 是 CPU，不复制 VexRiscv SoC 内部实现。
2. Buildroot、Linux、OpenSBI 后续全部重新 clone、固定版本并生成新的 Flow 配置。
3. Alan 上现有 `linux-on-litex-vexriscv`、Buildroot 和历史镜像只用于阅读启动顺序、目录组织和 LiteX 接口，不直接继续修改，不复用其工作树、输出目录、镜像或 RV32 配置。
4. 第一阶段不实现 virtio。根文件系统先使用 initramfs；真实存储留到 FPGA/Alpine 阶段。
5. 第一阶段不直接调 KCU105 DDR4。先使用 LiteDRAM controller + `SDRAMPHYModel`；上板时保留 controller/SoC 结构，只替换为 `USDDRPHY`。
6. 每个阶段独立提交、独立验证、独立停止；RTL 生成成功不等于 Linux 启动成功。

## 2. 硬件阶段

### H0：生产 RTL 与仿真 RTL 分离

- 新增 production/no-tandem Cluster 生成方式。
- production 配置关闭 ESTOP、自定义 printer/coreinst CSR 和 retire trace。
- simulation 配置继续保留这些验证接口。
- 核对 `misa`、编译 ISA、设备树 ISA 与实际指令实现一致。

**验收：** production RTL 不含仿真停止接口和 Tandem 端口；simulation 回归仍可使用原有监视器。

### H1：Linux 必需 CPU 语义补齐

- 实现 32-bit `EBREAK`，产生 breakpoint exception cause 3；验证 `C.EBREAK` 路径。
- 集中复核 M/S/U、异常委托、PMP、Sstc、Sv39、SFENCE.VMA、A/D 更新。
- 集中复核 RV64A、LR/SC reservation、AMO、`aq/rl` 和 trap 后 reservation 清除。
- 验证本地 `FENCE.I` 与四核 remote fence/I-cache 刷新边界。

**验收：** 定向架构测试与旧回归通过，不能只依赖 Linux 能否偶然继续启动。

### H2：LiteX/LiteDRAM 四核仿真 SoC

- 新建 Linux 专用 LiteX SoC 入口，不继续扩展 MCU runner。
- 四核 Flow Cluster 接入 LiteX 64-bit Wishbone。
- 接入 LiteDRAM controller 和 `SDRAMPHYModel`，初始内存容量 128 MiB。
- 保留 CLINT/IPI、PLIC 和 UART，但地址由 Linux SoC 配置统一产生。
- 增加确定的 reset、boot-hart 和 secondary-hart 停驻行为。
- boot ROM 只承担最小跳转；OpenSBI、DTB、Image、initramfs 放入主内存。

**验收：** 四核裸机探针可在随机 Wishbone/LiteDRAM 等待周期下完成，不出现 watchdog、fatal、assertion 或数据错误。

### H3：SoC 硬件闭环验证

- PLIC：priority、pending、enable、threshold、claim/complete、每 hart M/S context。
- CLINT：每 hart timer、IPI、并发清除和再次触发。
- UART：轮询收发、RX/TX 中断和真实可综合 PHY 接口边界。
- MMU：4 KiB/2 MiB/1 GiB 页、page/access fault、PMP、A/D 原子更新。
- SMP：四核同线读写、dirty owner 转移、四 sharer eviction、LR/SC 竞争、AMO。
- 内存：任意等待、背压、错误返回、Cache refill/writeback、复位期间禁止访问未就绪内存。

**验收：** 先完成硬件探针，再允许进入 Buildroot 软件迁移。

## 3. Buildroot 迁移前分析阶段

硬件 H0--H3 通过，只说明 SoC 具备运行 Linux 的基础能力，不代表现成 Buildroot
可以直接启动。重新 clone 之前必须先完成下面的分析并冻结接口。

### A0：拆解完整启动链

逐层确定 Flow 需要满足的契约：

```text
boot ROM
  -> OpenSBI
  -> Linux Image + DTB
  -> initramfs
  -> BusyBox init/shell
```

分别列出每一层的输入、输出、加载地址、运行特权级、依赖的 SBI extension 和失败标记。

### A1：分析 Buildroot 需要生成什么

- RV64 交叉工具链及 ABI；核对实际 ISA，不能照搬 VexRiscv RV32 配置。
- OpenSBI binary：选择 generic/FDT 还是 Flow platform，确认 HSM/TIME/IPI/RFENCE。
- Linux `Image`：确认 SMP、MMU、SBI、PLIC、串口和 initramfs 配置。
- rootfs：第一版只保留 BusyBox、init 和串口 shell，控制镜像容量。
- 最终产物清单及 hash：OpenSBI、Image、DTB、rootfs.cpio。

### A2：分析 LiteX 与 Linux 的平台接口

- 冻结 RAM 起始地址、容量和四个镜像的放置地址，检查重叠与对齐。
- 冻结 4-hart DTS：ISA、timebase、CLINT/ACLINT、PLIC context、UART、内存。
- 决定使用标准 16550 还是 LiteUART；驱动、DTS compatible 和硬件三者必须一致。
- 明确 LiteX 负责生成哪些地址/DT信息，禁止在 Buildroot 配置中重复写死另一套地址图。
- 明确仿真内存预加载与未来 FPGA boot loader/真实存储之间的边界。

### A3：审查现有资料，只提取参考结论

- 阅读 Alan 现有 linux-on-LiteX、Buildroot、OpenSBI 和启动日志。
- 记录其中可参考的启动顺序、LiteX DTS生成方式和镜像组织。
- 单独列出必须丢弃的 VexRiscv/RV32、旧Kernel、旧OpenSBI和历史板级假设。
- 不复制旧 `.config`、patch、binary、DTB、rootfs 或 `output/`；每项需求在 Flow
  上重新论证。

**分析阶段验收物：** 一份版本候选表、一份软硬件接口表、一份镜像内存布局、
一份 Flow Buildroot external tree 文件清单，以及重新 clone/迁移的原子阶段计划。
这些内容确认后，才允许进入下面的 B0。

## 4. Buildroot 重新迁移阶段

### B0：全新来源和版本清单

- 在 Alan 新建独立、干净的 Linux 工作目录。
- 重新 clone 并固定：Buildroot、Linux、OpenSBI、LiteX、LiteDRAM、LiteX-Boards。
- 记录每个仓库 URL、branch/tag、commit SHA 和工具版本。
- 不复制现有 Buildroot `.config`、`output/`、Image、OpenSBI binary、DTB 或 rootfs。

### B1：新增 Flow 专用 Buildroot external tree

需要新建而不是修改 VexRiscv 配置：

- `flow_rv64_defconfig`：RV64、正确 ABI、BusyBox、initramfs。
- Flow Linux kernel config：SMP、RISC-V SBI、MMU、PLIC、8250/LiteUART 中实际选定的一种。
- OpenSBI 配置：优先 generic/FDT；若硬件接口无法表达，再新增最小 Flow platform。
- Flow board 目录：rootfs overlay、post-image、镜像布局和启动参数。
- 由 LiteX 实际 SoC 生成 DTS/DTB，包含 4 个 hart、ISA、内存、CLINT/PLIC/UART。
- 可复现 runner：生成镜像、放置地址、启动仿真、超时和日志检查。

### B2：四核 Buildroot 启动门

依次验证：

1. boot ROM 进入 OpenSBI；
2. OpenSBI 报告 4 个 hart；
3. Linux 进入 S-mode；
4. Linux 识别 `4 CPUs`；
5. Sv39、timer、IPI、PLIC 和 console 正常；
6. initramfs 挂载成功；
7. BusyBox shell 可执行命令；
8. 无 kernel panic、oops、hang、watchdog、fatal 或 assertion。

只有 1--8 全部成立，才称为“四核 Buildroot PASS”。

## 5. FPGA DDR4 与 Alpine 阶段

Buildroot 仿真通过后才进入：

- 新建当前四核 Cluster 的 KCU105 target，不能复用历史单核 target 作为完成证据。
- 将 `SDRAMPHYModel` 替换为 KCU105 `USDDRPHY + DDR4`。
- 验证 DDR calibration、BIST/memtest、长延迟、Cache 和错误路径。
- 增加 SPI Flash 或 SD 卡启动/根文件系统；不把 virtio 作为板级主存储方案。
- Buildroot 先在真实存储上通过，再制作并启动 Alpine rootfs。

## 6. Git 与证据规则

- 本计划只在本地创建；当前阶段不修改 RTL，不 clone Buildroot。
- 后续开发放在 Alan 的独立干净目录，按原子阶段 commit 并 push；本地仓库只通过 Git pull 查看进度。
- 每次 Alan 验证记录：目标 SHA、完整命令、退出码、关键启动标记、hart 数、fatal/watchdog 扫描和日志路径。
- 不覆盖或清理本地现有未跟踪文档与构建产物。

## 7. 下一步停止点

下一步先把硬件 H0--H3 的实施和验证计划冻结。硬件达到验收门后，只进入
Buildroot A0--A3 分析，不立即 clone。A0--A3 的分析结果未经确认，不进入 B0。
