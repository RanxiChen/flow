# BE2 普通单核启动调试版

最新板上结果（2026-10-12 07:27/07:29 CST）：已在Alan成功抓取两段匹配ILA波形，核心持续退休，但困在BIOS的UART中断处理循环。波形和匹配生成RTL共同定位64位AXI-Lite→Wishbone→32位CSR读桥丢失地址bit2，enable读错子槽和数据位置；Linux启动未通过。具体PC、总线值及原始证据见 [板上抓取报告](BE2-single-debug-capture-20261012.md)。下文“未capture/正在构建”条目是此前阶段记录。

源码复现：新增13项桥接/原生UART定向检查，Alan源0b4e18b实际7PASS/6FAIL、exit1。组合桥高半部读错、读strobe误打相邻CSR、BIOS服务不前进及满TXFIFO状态回读0均复现；单独两桥的明确半部/完整word对照PASS。生产代码未修复，golden不变，详见 [源码测试与分析](BE2-mmio-csr-analysis-20261012.md)。

最终调试硬件已生成：源 `b8e62666de08752a96fd27b838a2fcd79536d3da`，Alan目录 `/home/chen/FUN/flow-runs/be2-single-debug-20261012/gateware/`，文件 `xilinx_kcu105.bit` 与匹配 `xilinx_kcu105.ltx`；本地副本为 `build/be2-products/single-debug-20261012/`。Vivado/build runner exit0，100MHz最终 WNS+0.064ns/WHS+0.004ns/WPWS+0.103ns，TNS/THS/TPWS与全部failed endpoints0，route errors0，SD内部IO预算PASS。ILA/debug hub另有4条bus-skew约束，全部通过、最小slack+9.326ns；未将这类独立检查遗漏在根时序表之外。尚未取得新bit的板上capture，核心是否正常运行仍未判断。

bit SHA256=`1549f11d09986ccc6454162bc8c66f6de995a4afac84a4f0495c91ae46a20480`；ltx SHA256=`22ce77df65a82d95cbb8b454388cf3147912ab108ae36e515a29cd4bb835e866`。129项source-snapshot、BIOS与ROM初始化、功能配置相同、161probe/1896bit再次核对，完整binding和原报告在 `records/be2-single-debug-20261012/final/`。debug hub的`C_ENABLE_CLK_DIVIDER=false`，保留工具默认频率参数，不启用额外MMCM；实际ILA采样与sys_clk均100MHz，使用1MHz JTAG脚本。默认属性不替代实际clock/timing报告。

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

新增约束仅为Vivado生成的ILA/debug hub IP自身约束，没有新增用户false-path/multicycle、没有豁免CPU或AXI路径。准备好的 `fpga/kcu105/capture_single_debug.tcl` 仅在用户已加载匹配bit后读取ILA，输出CSV/VCD/原生ILA文件；不烧录或复位CPU。2022.2本机help确认 `wait_on_hw_ila -timeout` 单位为分钟，脚本使用0.5（30秒），不能沿用其他版本示例中的5000。

实际运行结果、源SHA、主机、exit、bit/ltx路径后续按原始报告补充。此时状态为准备，尚未生成调试bit，也没有上板抓取结果。

使用新调试bit时，在Vivado Hardware Manager设置对应 `.ltx` 为Probes文件，再立即抓取。可先看 `dbg_seen_retire`、`dbg_last_retire_pc`、`dbg_retire_valid`、`dbg_hart_fatal` 和两组 `dbg_*_monitor_*`；它们分别判断是否执行过指令、目前停点、是否继续退休，以及请求/响应是否前进。连续4096周期为40.96微秒，不能据单个短窗没有退休就断定死锁；结合sticky停顿原因和持久计数判断。要抓启动过程，可先arm，再由用户按板上复位按钮，以ROM地址 `0x10010000` 的AR握手或首次退休为触发。ILA/debug hub均使用sys时钟；sys不运行时无法采样，debug core不可访问本身只构成时钟/连接排查线索。

2026-10-12 06:27 CST：b8e62666de08752a96fd27b838a2fcd79536d3da 在Alan独立worktree `/home/chen/FUN/flow-be2-single-debug-20261012` 执行；每次启动重读共享配置，首选cloud_chen均5秒SSH timeout255，Alan预检Java11.0.32.1/Verilator5.028/GCC13.2.0、磁盘19GiB及内存可用后回退。运行CPU6–9/nice10/maxThreads4；同步用本地反向HTTP代理，无bundle。

新增monitor测试同时覆盖AXI4/AXI-Lite两种接口、RLAST、W早于AW、同拍请求/响应、双响应错误、反压不计接受和无总线驱动；1项含两subtest PASS。原ILA检查2/2 PASS，原BreezeHangMonitorSpec5/5 PASS，fail/cancel/ignore/pending均0。普通标量生产RTL/config对原7f差异为空（仅4个RVV文件不同，此配置不实例化），debug/Tandem使用原可选路径。

第一次19af09f检查捕获AXI-Lite兼容`last`字段导致read计数不归零，原期待值保留，a3fecd7按接口类型修复；失败日志/exit1归档。随后a3fecd7最小全部通过，但新worktree的CVFPU嵌套依赖未初始化，生成报include directory missing；sbt后台runMain异常虽打印success，后续missing-file门禁正确拒绝prepare。补齐准确common_cells=6aeee85d0a34fedc06c14f04fd6363c9f7b4eeea/fpu_div_sqrt_mvp=86e1f558b3c95e91577c41b2fc452c86b04e85ac，b8e6266改fork传播异常并立即检查生成manifest，重新生成exit0；这次不是RTL/golden失败，也没有换主机掩盖失败。

BIOS/工程prepare成功，prepared-audit核对129个不可变源文件SHA、ROM内BIOS前缀、单核所有功能参数与原产品一致（仅tandem/debug打开）。ILA6.2共161probe/1896bit、深度4096、输入流水2拍，正在Vivado默认100MHz实现，未有最终timing/bit结论。报告和原始记录见 `records/be2-single-debug-20261012/prepared/`。

用户确认JTAG连接Alan；06:24只读Vivado/hw_server discovery未发现目标，USB列表只识别CP2105串口桥，尚无波形或CPU工作状态证明。后续以实际target与capture为准；这不阻塞bit构建，不烧写Flash。

综合完成：WNS+1.492ns（仅综合估计）、LUT74347/FF57881/BRAM264.5tiles/DSP23，0error/0critical warning。ILA容量可容纳，Vivado已进入place；最终routed timing另记，综合hold负值不是最终hold结果。主机上的Tandem/ILA marker与129项source-snapshot、BIOS ROM前缀已核对。原综合报告按阶段归档。

另查旧板级记录和当前CP2105双接口：FPGA用户串口应选Standard通道（Alan现为ttyUSB1/if01），ttyUSB0/if00为Enhanced系统控制器通道。AMD文档亦说明该分工：https://docs.amd.com/r/en-US/pg235-v-hdmi-tx-ss/KCU105-Board-FMCH-VADJ-Adjustment 。此为“只打印0”的待核对候选，尚未收到用户所用串口确认，不宣称已定位原因。没有打开或写用户串口。

本地实际bit/ltx逐项匹配最终binding SHA256，五文件CoreMark+原GAPBS软件包重新绑定实际bit/BIOS/CSR，下载及SD安装均校验通过。原四核/普通单核/RVV三个交付产品未覆盖；本次是额外的普通单核调试产品。Tandem为退休观测，波形未取得时不推断CPU健康。新增软件及SD审计见 [CoreMark/GAPBS报告](BE2-coremark-gapbs-sd-report.md)。

交付前再次只读发现JTAG：hw_server localhost3121的target count仍为0，discovery exit0（quiet枚举正常返回空，不代表找到板）；final console/exit归档。因此此次没有program/reset/ILA capture，单核健康状态未判断。编译成功及SD准备不能替代板上执行证明。
