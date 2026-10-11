# BE2 单核板上 ILA：UART 中断处理循环

2026-10-12 07:27:47、07:29:12 CST，在 Alan 通过 JTAG 成功抓取两段波形。核心持续退休，但采样均困在 BIOS 的 `isr → uart_isr → PLIC complete → claim` 循环中，没有执行 Linux。两段各4096周期（100MHz下40.96µs），分别退休347、348条，均只有相同25个PC，最大连续无退休35周期。两次相隔85秒仍是同一循环，MMIO计数持续增加；这是软件执行无法离开中断处理的证据，不能把“有退休”算成启动通过。

## 实际执行

| PC | 指令 | 对应行为／采样结果 |
|---|---|---|
| `0x1001016c` | `lw s0,0(s1)` | `isr` 读 PLIC claim `0x0c200004`，每次得到10（UART） |
| `0x100101a2` | `jalr a5` | 调用 `uart_isr`，函数地址 `0x10013834` |
| `0x1001383e` | `lw a4,16(a2)` | 读 `uart_ev_pending`，地址 `0x12001010`，核心得到3 |
| `0x10013840` | `lw a5,0(a5)` | 读 `uart_ev_enable`，地址 `0x12001014`，核心得到0 |
| `0x10013844` | `and a5,a5,a4` | `stat = pending & enable = 0` |
| `0x1001384a / 0x10013876` | 条件分支 | 跳过RX和TX处理，未清UART中断来源 |
| `0x100138b4` | `ret` | UART handler返回 |
| `0x100101a4` | `sw s0,0(s1)` | PLIC complete写10，下一轮又claim到10 |

每段都有14次claim读、14次pending读、14次enable读，以及14次PLIC complete写。所有采样中的AXI响应均OKAY，sticky error/accounting_fault/fatal/estop/hang均0，PLL locked1、reset0。这只说明采样窗口内握手及这些监控未报错，不是全系统正确性证明。窗口内memory AXI无新事务，不能据此宣称DDR已初始化或SD/Linux已启动。

## 波形和实际生成RTL共同定位的问题

两次抓取中，每个 `0x12001014` 读请求的AXI-Lite响应都是 `0x0000000000000003`，而该load退休结果为0。相邻 `0x12001010` 读也响应相同的3，核心得到3。PLIC `0x0c200004` 响应为 `0x0000000a00000000`，核心正确得到10，说明不能以修改核心的高32位取数来解决CSR问题。

已核对匹配调试产品的生成RTL（SHA256 `3b5cf26a7b6637ce767ac8b226a377d1857cff11646672f80518a991b3a68754`，与构建binding一致）：

1. MMIO AXI-Lite→64位Wishbone桥在读状态使用 `adr = araddr[31:3]`、`sel = 8'hff`，原地址bit2没有传给Wishbone。
2. 64位Wishbone→32位CSR桥以`sel`选择CSR子槽。上下半部sel均有效时，低半部优先，`selected_alignment=0`；CSR地址由`{wishbone_adr, selected_alignment}`组成。
3. 因此读 `0x12001014` 被映射到 `0x12001010` 的pending子槽，返回数据放在低32位。核心按原地址bit2从高32位取enable，得到0，UART handler跳过处理，但实际UART中断继续assert，PLIC complete后立即重新claim。

这是CSR桥接的地址／数据位置问题，足以解释当前中断循环。原串口“只打印0”的完整启动过程没有抓到，不能断言每个历史症状都已证明由此产生。修复应保留32位CSR地址步长与64位MMIO数据位置契约，避免对UART读数据简单复制、屏蔽中断或改golden；同时检查其他CSR上半部访问及读副作用。当前抓取任务未改RTL、未重新烧录，也未复位CPU。

## 版本、命令与原始证据

硬件源 `b8e62666de08752a96fd27b838a2fcd79536d3da`，单核SD/Tandem/native ILA6.2，Vivado2022.2；实际target `localhost:3121/xilinx_tcf/Digilent/210308A7B107`，器件xcku040、index0。用户报告已烧录；工具识别1个ILA并接受匹配LTX、触发和上传成功，没有执行bitstream读回，不能把本地bit哈希表述为板上读回证明。

匹配bit SHA256 `1549f11d09986ccc6454162bc8c66f6de995a4afac84a4f0495c91ae46a20480`；LTX SHA256 `22ce77df65a82d95cbb8b454388cf3147912ab108ae36e515a29cd4bb835e866`；反汇编BIOS ELF SHA256 `0148d5e63a185b4f80a5c230c120f6ef6c1623999086b8b27ec052a6412cb13a`，取自该产品的 `software/bios/bios.elf`，BIOS binary/ROM匹配构建binding。

实际cwd `/home/chen/FUN/flow-runs/be2-single-debug-capture-20261012-second`。对每个snapshot执行：

```sh
taskset -c 4-5 nice -n 10 /home/chen/Tool/FPGA/Vivado/2022.2/bin/vivado \
  -mode batch -log capture.log -journal capture.jou \
  -source /home/chen/FUN/flow-runs/capture_single_debug.tcl \
  -tclargs /home/chen/FUN/flow-runs/be2-single-debug-20261012/gateware/xilinx_kcu105.ltx \
  /home/chen/FUN/flow-runs/be2-single-debug-capture-20261012-second/snapshot1
```

第二次对应`capture2.log/capture2.jou/snapshot2`，两次exit0。脚本仅重置ILA采集配置并立即触发，JTAG1MHz，未program/reset CPU、未使用VIO、未驱动总线。首次启动把`-log/-journal`置于`-tclargs`之后，Vivado将它们当成Tcl参数，使用检查exit1，在连接硬件之前退出；失败console和exit保留，随后调整选项顺序。

原始CSV/VCD/ILA及日志完整保留在上述Alan目录。本地证据 `records/be2-single-debug-20261012/captures/20261012-first/`；提交CSV/VCD的无损gzip、原生ILA、console/exit、BIOS反汇编、实际RTL片段、`analysis.json`及可重跑的 `analyze.py`。CSV SHA256分别 `da92c2db68a839337203a92488978c5eb61c88495a3778df38a4b1fc20dbf8af`、`849d9d8a74630fa0d1cc7fd903d85945efb83d8c793b340a190cda561f2710d2`。统计只计`retire_valid=1`样本；MMIO地址与响应按接受顺序配对，未将valid为0时残留payload算成退休。
