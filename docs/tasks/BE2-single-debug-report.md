# BE2 普通单核启动调试版

症状：用户加载单核 SD 产品后，串口只出现一个 `0`，没有后续 BIOS 信息。原产品单核源7f7f186、100MHz时序通过；此板上现象尚未定位，不能视为 Linux/SD 已启动。

本次按用户要求保持同一普通单核配置、SD/BIOS接口，开启 `tandem` 与 `fpga-debug`，重新生成独立bitstream。JTAG使用Vivado原生ILA/debug hub；不包含标准RISC-V DMI/OpenOCD单步模块。现有三个交付产品保留。

## 观测与触发

- 原完整Tandem退休字段、最后退休PC/指令、hartFatal/hartEStop、noRetireCycles及hangReasons。
- 内存AXI4和MMIO AXI-Lite全部五通道valid/ready，地址/数据/strb/响应；内存额外ID、burst长度、last。
- 两组新增只读监控器：各通道32-bit握手计数、最后AR/AW地址及R/B响应、SLVERR/DECERR计数及sticky error、8-bit未完成read/write计数、write_active、stall_cycles及sticky accounting_fault。统计按所有ID聚合，不替代协议验证。
- 路由器ROM/SRAM/DDR去向、L1D/L2事件、PLL locked、系统reset。采样时钟sys100MHz，ILA深度4096、输入流水2拍。

监控器先寄存握手/地址/响应，计数和last字段在相应事件后一拍更新。ILA输入流水对所有探针另加同样的2拍；原始总线探针与监控统计有这1拍相对差。32-bit计数回绕，8-bit未完成计数饱和并记录fault；read仅RLAST接受时完成，AXI-Lite每次R接受完成。W先于AW也设置write_active，直到接受B；未完成write计数严格以AW/B记账。没有总线驱动、ready门控、超时取消或CPU反馈。

使用时先立即抓取：看seen_retire、last_retire_pc、两组计数与pending，以及hart_fatal/hang。随后针对停点设置触发并重启：hang上升、fatal、response error、MMIO UART地址0x12001000握手。hangReasons bit0为无退休，1..10为mem/mmio AR/R/AW/W/B连续阻塞，11..14为最老mem read/write和mmio read/write完成等待；阈值保持10000000周期（100MHz下0.1秒）。WFI/合法长等待也可能触发，hang本身不等于硬件死锁。

## 构建与证据

构建脚本 `records/be2-single-debug-20261012/build.sh`：新增monitor与原ILA连接测试、既有BreezeHangMonitorSpec、准确源独立RTL生成、BIOS/项目准备、Vivado实现、完整时序门禁后生成bit/ltx。保持100MHz/原时序例外；增加调试探针会改变资源和物理结果，不能复用普通单核旧时序。

实际运行结果、源SHA、主机、exit、bit/ltx路径后续按原始报告补充。此时状态为准备，尚未生成调试bit，也没有上板抓取结果。
