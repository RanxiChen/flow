# Flow 运行 Buildroot 与 Alpine 的硬件需求结论

**日期：** 2026-08-21  
**执行边界：** 板卡最后做；在此之前全部使用 LiteX/Verilator 软件仿真  
**硬件目标：** 一套 SoC 同时支持四核 Buildroot 和 Alpine，不为两个发行版设计两套硬件

## 1. 最终结论

必须把 Flow SoC 收敛为下面这一套硬件：

```text
4 x RV64GCA_Zicsr_Zifencei Flow Core
  + M/S/U privilege
  + Sv39 MMU + PMP
  + private coherent L1D, private L1I, shared L2/Home
  + CLINT/ACLINT-compatible timer and IPI
  + PLIC with per-hart supervisor contexts
  + 16550 UART
  + boot ROM
  + 256 MiB LiteDRAM simulation memory
  + LiteX-generated device tree
```

这套硬件完成后：

- Buildroot 使用 BusyBox initramfs，可直接在内存中启动，不需要块设备和网络；
- Alpine 第一门使用 Alpine minirootfs/initramfs，也不需要新增 CPU 硬件；
- Alpine 完整门再增加 SD 卡控制器；需要在线 `apk` 时再增加以太网。

## 2. CPU 必须具备的硬件

| 硬件 | Buildroot | Alpine | 理由 |
|---|---:|---:|---|
| RV64I | 必须 | 必须 | 64 位内核和用户态基础 |
| M | 必须 | 必须 | 正常工具链和用户态会生成乘除法 |
| A | 必须 | 必须 | 四核 Linux 自旋锁、原子变量和页表 A/D 原子更新 |
| C | 必须 | 必须 | 本项目冻结为 RV64GC，官方发行版二进制兼容按该目标验证 |
| F/D + 32 个浮点寄存器 | Buildroot 可不用，但本项目必须实现 | 必须 | Alpine 预编译 riscv64 用户态按 LP64D 兼容目标处理；LP64D 要求 D |
| Zicsr | 必须 | 必须 | 特权态、计时器、异常和 SBI 运行基础 |
| Zifencei | 必须 | 必须 | 代码装载、跨 hart 指令可见性和 OpenSBI RFENCE |
| M/S/U | 必须 | 必须 | OpenSBI 在 M 态，Linux 内核在 S 态，进程在 U 态 |
| Sv39 | 必须 | 必须 | RV64 Linux 用户进程、虚拟内存、mmap/fork |
| PMP | 必须 | 必须 | OpenSBI 必须向 S 态开放合法物理内存并保护驻留固件 |
| precise trap | 必须 | 必须 | page/access fault、illegal、ecall、breakpoint、misaligned 必须精确 |
| time CSR与稳定timebase | 必须 | 必须 | Linux时钟、调度和用户态时间接口 |
| mcycle/minstret | 建议保留 | 建议保留 | 性能统计和诊断有用，但不是启动Linux的硬门槛 |

### 当前必须修正

1. 实现真正的 `EBREAK`，产生 cause 3；`C.EBREAK`必须走同一异常路径。
2. production RTL关闭 ESTOP、自定义 printer/coreinst CSR和Tandem trace。
3. `misa`、Linux DTS ISA、Buildroot `-march/-mabi`三者必须完全一致。
4. 对 F/D 做Linux级验证：FS状态、异常标志、NaN boxing、进程/中断上下文保存、四hart独立状态。
5. 对RV64A做Linux级验证：全部AMO、LR/SC竞争、`aq/rl`、trap后reservation清除。

## 3. Cache与多核必须具备的硬件

### 必须

- 四个hart的私有L1D必须由共享L2/Home维持一致性。
- 同一Cache line必须支持4 sharer、dirty owner转移和inclusive eviction。
- 页表A/D硬件更新必须走一致的原子路径，不能绕过Cache一致性。
- 每hart私有TLB；`SFENCE.VMA`必须正确处理VA、ASID和global页。
- OpenSBI RFENCE必须能够向其他hart发送IPI并执行remote `FENCE.I`/`SFENCE.VMA`。
- CPU到RAM和MMIO的请求必须支持任意等待、背压和错误返回。

### 不要求

- 不需要MESI的E状态；正确的MSI足够。
- 不需要非阻塞Cache、多MSHR、乱序访存或高IPC。
- Buildroot/Alpine第一版没有DMA，因此第一阶段不需要DMA一致性端口。

## 4. SoC必须增加或冻结的硬件

### 4.1 Reset和四hart启动

- 所有hart上电进入M态、`satp=0`、同一确定reset vector。
- 只允许一个hart执行cold boot；另外三个hart由OpenSBI停驻。
- Linux通过SBI HSM有序启动另外三个hart。
- reset必须清除Cache valid、TLB、PLIC/CLINT状态和LR/SC reservation。

**理由：** Linux推荐有序SMP启动；仅让四核同时从一段裸机代码跑起来不等于HSM可用。

### 4.2 Boot ROM

- 使用可综合只读ROM，内容只做最小初始化和跳转。
- 仿真器把OpenSBI、Image、DTB和initramfs预装到LiteDRAM。
- RV64 Linux `Image`起始地址必须2 MiB对齐。
- 跳入Linux前由OpenSBI保证`a0=boot hartid`、`a1=DTB地址`、`satp=0`。

### 4.3 内存

- SoC物理内存窗口从当前固定32 MiB改为参数化。
- Buildroot和Alpine仿真统一使用256 MiB LiteDRAM地址空间。
- 使用LiteDRAM controller + `SDRAMPHYModel`，不再把`integrated_main_ram`当最终内存结构。
- 板卡阶段只把PHY替换为KCU105 `USDDRPHY`；CPU/L2/总线接口不变。

**理由：** 256 MiB足够容纳RV64内核、OpenSBI、DTB、Buildroot initramfs和最小Alpine rootfs，同时避免一开始引入板级DDR校准。

### 4.4 Timer和IPI

- 保留CLINT兼容的per-hart `msip`、`mtimecmp`和共享`mtime`。
- 设备树必须能被OpenSBI generic平台的FDT timer/IPI驱动识别。
- 必须支撑SBI TIME、IPI、RFENCE和HSM。

### 4.5 PLIC

- 保留一个PLIC，提供每hart M context和S context。
- UART使用固定PLIC source ID；DTS和RTL必须一致。
- 必须验证priority、pending、enable、threshold、claim/complete及电平中断再次触发。

**理由：** earlycon可轮询，但稳定的交互式Linux串口需要正常外部中断路径。

### 4.6 UART

- 第一版确定使用16550，不同时维护LiteUART和16550两条Linux驱动路线。
- 仿真接serial console model；production接口接真实UART PHY。
- 必须实现Linux 8250驱动实际使用的RBR/THR、IER、IIR、LCR和LSR语义。

**理由：** 16550有标准OpenSBI和Linux驱动，可避免继续携带VexRiscv/LiteUART专用内核补丁。

### 4.7 Device Tree接口

设备树不是硬件，但它是硬件对OpenSBI/Linux的唯一真实描述，必须由LiteX SoC配置生成：

- 4个CPU节点和正确hart ID；
- `rv64imafdc_zicsr_zifencei`；
- timebase frequency；
- 256 MiB内存；
- timer/IPI节点；
- PLIC及8个context；
- 16550、时钟和PLIC source；
- OpenSBI驻留内存reserved region；
- `/chosen/stdout-path`、initrd start/end和bootargs。

## 5. Buildroot硬件边界

Buildroot PASS只要求上面第2--4节的硬件，不增加以下设备：

- 不加virtio；
- 不加SD卡、SPI Flash、SATA、PCIe、USB；
- 不加以太网；
- 不加GPU、显示器、键盘；
- 不加RTC和硬件随机数；
- 不加U-Boot/UEFI硬件。

Buildroot使用OpenSBI -> Linux ->内存中的BusyBox initramfs。验收必须看到：

```text
OpenSBI HART Count = 4
SBI TIME/IPI/RFENCE/HSM available
Linux: 4 CPUs brought up
PLIC/UART/timer initialized
Run /sbin/init as init process
BusyBox shell可输入命令
```

## 6. Alpine在同一硬件上增加什么

### Alpine第一门：minirootfs/initramfs

不新增硬件。继续使用同一个OpenSBI、Linux Image、DTB、UART和256 MiB LiteDRAM。

必须额外验证的是软件ABI触发的硬件行为：

- Alpine动态链接器能执行；
- musl、BusyBox、OpenRC启动；
- LP64D程序和浮点上下文正确；
- `fork/exec/mmap/page fault/signal`正确；
- 四核调度和原子操作稳定。

### Alpine第二门：持久化系统

增加一个LiteSDCard控制器和可仿真的SD卡镜像，根文件系统使用ext4。未来KCU105本身有micro-SD接口，因此该选择可以从仿真延续到板卡。

如果LiteSDCard通过DMA直接访问主内存，则必须二选一并冻结：

1. DMA通过L2/Home coherent-I/O端口；或
2. 明确的non-coherent DMA模型，加Linux驱动cache maintenance。

第一版不能让DMA绕过L2后仍假定Cache自动一致。

### Alpine第三门：可联网使用

需要在线`apk`时增加LiteEth MAC、仿真PHY/网络模型和PLIC中断。若网卡使用DMA，同样必须经过coherent-I/O或non-coherent DMA维护路径。

以太网不是“Alpine能启动”的条件，是“Alpine能正常联网安装软件”的条件。

## 7. 明确推迟到板卡阶段的内容

- KCU105 PLL/MMCM和时钟约束；
- `USDDRPHY`、DDR4管脚、校准和BIST；
- 真实UART电气接口；
- micro-SD管脚和电平转换；
- Ethernet PHY/MDIO/RGMII或1000Base-X；
- bitstream生成、下载和板级复位。

这些内容不阻止Buildroot和Alpine在LiteX/Verilator中完成软件闭环。

## 8. 实施顺序

```text
P0  production/simulation RTL分离 + EBREAK
P1  RV64GC/Sv39/PMP/RV64A集中验证
P2  256 MiB LiteDRAM仿真SoC + boot ROM
P3  CLINT/PLIC/16550/HSM/RFENCE硬件探针
P4  全新Buildroot分析、clone和四核BusyBox shell
P5  Alpine minirootfs/initramfs
P6  仿真LiteSDCard + ext4持久化Alpine
P7  仿真LiteEth + apk联网
P8  最后迁移KCU105 DDR4、micro-SD和网络PHY
```

在P4以前不clone Buildroot；在P5以前不下载Alpine；在P8以前不做板级PHY和约束。

## 9. 判断依据

- Linux RISC-V启动要求：`a0=hartid`、`a1=DTB`、`satp=0`、RV64 Image 2 MiB对齐，并优先使用SBI HSM有序启动。
- OpenSBI generic平台依赖FDT中可识别的timer、IPI和console节点；多hart必须有IPI设备。
- Buildroot负责生成工具链、bootloader、Linux和rootfs；initramfs可以在没有块设备时启动。
- Alpine官方支持riscv64；本项目按预编译LP64D用户态兼容目标设计，因此F/D不再作为可选项。
- KCU105具有micro-SD接口；LiteX生态提供LiteDRAM、LiteSDCard和LiteEth。
