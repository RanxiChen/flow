# BE2 MMIO→CSR 源码定向测试与问题分析

2026-10-12：按用户“在源代码这些位置加测试，分析问题”执行。新增 `sim/litex/test_mmio_csr_bridge.py`，实际在Alan运行生产LiteX桥接类和CSRBank/LiteUART。最终13项，**7 PASS、6 FAIL、0 ERROR、0 SKIP，进程exit1**。六项失败是仍未修复的产品缺陷，保持普通失败断言，没有xfail、删场景或修改golden。本轮只改测试和报告，没有生产RTL/桥接代码修改、Vivado重建、重新烧录或CPU复位。

## 需求到结果

所有数据期望由软件CSR地址、32位寄存器值、64位总线数据位置和精确一次副作用独立给出。对照slot4=`0x11223344`、slot5=`0x55667788`，不能用相同值掩盖地址别名。读/写等待均有128周期测试超时；有限反压5或7周期。AW/W同拍、AW早4周期、W早4周期均覆盖。

| 需求 | 测试方法（`MmioCsrBridgeTest`） | 最终结果 |
|---|---|---|
| 明确给出Wishbone高/低半部sel时，CSR地址和返回位置正确，各读一次 | `test_direct_wishbone_csr_both_lanes` | PASS |
| AXI桥连接返回完整64位数据的从设备时，两半部LW取数正确，反压时响应稳定 | `test_axi_bridge_full_width_slave_both_lanes` | PASS |
| 串接两桥后的低半部CSR读取 | `test_composed_lower_csr_read` | PASS |
| 串接两桥后的高半部CSR读取 | `test_composed_upper_csr_read` | FAIL：期望`0x55667788`，得到0，原AXI数据是相邻slot4的`0x11223344` |
| 只触发所请求CSR的读选通 | `test_composed_upper_read_targets_only_requested_csr` | FAIL：slot4计数1、slot5计数0，期望相反 |
| 高半部读取在R反压期间保持正确数据 | `test_composed_upper_read_with_r_backpressure` | FAIL：错误数据稳定保持；不是数据随反压变化 |
| 低半部反压读取没有重复读副作用 | `test_composed_lower_read_backpressure_no_duplicate_side_effect` | PASS |
| 两半部写及AW/W独立握手，没有重复写 | `test_composed_writes_both_lanes_and_independent_aw_w` | PASS：两slot各写3次 |
| WSTRB=0不产生CSR写副作用 | `test_composed_zero_write_strobes_have_no_csr_side_effect` | PASS |
| 原生UART pending/enable回读真实存储值 | `test_native_uart_pending_and_enable_readback` | FAIL：实际enable已写成3，回读0；pending回读3 |
| BIOS UART服务必须清除有限fixture的IRQ | `test_native_uart_bios_service_clears_irq` | FAIL：连续3次stat=0、IRQ=1，与板上循环一致 |
| TX FIFO满时TXFULL应读1 | `test_native_uart_full_tx_fifo_status_reads_one` | FAIL：内部TXFULL=1、TX已排队，CSR回读0 |
| 读取TXFULL不能消耗已经收到的RX字节 | `test_native_uart_txfull_read_does_not_pop_rx_fifo` | PASS：本版默认RX消费由写pending清除触发，读取RXTX不直接弹FIFO |

原生UART使用同一生产类、默认`rx_fifo_rx_we=False`、同一32位CSRBank地址步长；为Migen内置仿真将FIFO深度设1，使用寄存器Buffer，未实例化FPGA串口PHY、实际深度16 FIFO或完整CPU/PLIC。这能验证寄存器、事件和桥接行为；完整BIOS机器码、CPU精确异常和板上启动仍以对应系统/板上证据为准。BIOS服务测试按已有handler语义执行，TX软件环为空、RX软件环有空间，三个服务尝试处理单个RX字节；它不伪称执行了完整BIOS ELF。

## 根因与正确的修复边界

| 边界／源码 | 本来提供的语义 | 组合后的问题 |
|---|---|---|
| Flow `L1DMmio.scala:48`、`L1DCache.scala:26` | AR传原始字节地址；返回64位beat按地址低3位取LB/LH/LW，保留正常64位访存语义 | 核心按bit2取高半部本身符合当前契约；不能改成所有MMIO都取低半部 |
| LiteX `axi_lite_to_wishbone.py:18` 的 `AXILite2Wishbone` | 64位word地址为AR地址`[31:3]`；read sel全部有效；普通64位从设备应返回完整word | 这一步不保留原始地址bit2。**对完整64位从设备，本步骤本身不构成错误**，两半部对照已PASS |
| LiteX `wishbone.py:793` 的 `Wishbone2CSR` | 利用sel选择32位CSR子槽及返回数据半部；两半部sel均有效时低半部优先 | 上游sel恒`0xff`，只有低半部可被选择，高半部CSR无法读到 |
| LiteX `csr_bus.py` 的 `CSRBank` | 按CSR子槽产生读/写strobe并同步返回数据 | 高半部AR实际触发低半部CSR读strobe，问题包含地址别名，不能仅移动返回数据 |
| LiteX `uart.py`、`csr_eventmanager.py` | handler读`pending & enable`，实际IRQ也按真实enable生成 | enable回读0但真实存储仍3，handler不处理RX/TX，IRQ持续，PLIC正常complete后重新claim |

具体例子：`0x12001014 → WB word 0x02400202, sel 0xff → CSR槽0x404（pending）`，应该访问的enable槽是`0x405`。返回`0x0000000000000003`，LW高半部得到0。显式高半部Wishbone读使用sel`0xf0`则能访问正确槽，并返回`0x5566778800000000`，对照PASS。该复现与两次板上ILA及匹配生成RTL一致，详见 [板上抓取报告](BE2-single-debug-capture-20261012.md)。

归责应写成**64位AXI-Lite/Wishbone与“单CSR子槽”桥的组合契约不匹配**，不能笼统称为AXI桥不支持高半部，也不能把PLIC返回高半部10改成低半部来绕过。`FlowPlic.sv`实际返回包含threshold/claim的完整64位word，板上claim响应`0x0000000a00000000`、核心得到10，属于应保留的正常行为；本轮完整宽度从设备对照是fixture，没有冒充重新跑了FlowPlic RTL。

进一步影响：UART TXFULL位于`+4`，在满/空两种状态中都可能被回读为0。FIFO空时正确值也是0，仅测空队列会掩盖问题；满队列用例明确FAIL。其他采用相同CSR桥的控制器也有奇数槽读风险，包括SD/DDR相关CSR，尚未逐寄存器执行，因此不能宣称其具体故障已实测。四核、单核、单核RVV共享这条SoC外壳路径，应在同一修复中覆盖，不能只修改BIOS UART handler。

建议修复归属为**CSR区域的桥接／适配边界**：在原始字节地址仍存在时保留CSR子槽选择，返回值放到正确的64位数据半部，AR到CSR读副作用严格一次，R反压只保持响应；AW/W独立握手及WSTRB行为保留。完整64位的PLIC/CLINT等区域按原契约处理。仓库外的可编辑LiteX安装未改，不采用复制返回值、屏蔽中断或改核心load格式的绕过。

修复设计还必须处理两个边界：AXI-Lite AR没有读size/strobe，不能根据地址把所有设备读强制降成32位；普通64→32宽度转换器若自动读取两个相邻CSR，也不能忽略额外读副作用。应明确CSR宽度访问与完整64位设备访问的适配边界，再用本组原期望回归；本轮不自行实施这些生产变更。

本轮没有改变精确异常、程序顺序、MMIO最老项发起或kill语义，相关生产树与调试硬件源`b8e6266`完全相同。本轮FAIL证明了产品的CSR读取与软件前进缺陷，不能作为“正常工作/无死锁通过”；不能把真实的循环靠放宽测试、超时或IRQ屏蔽改成PASS。

## 现有覆盖为什么漏掉

`sim/litex/test_liteuart.py`直接驱动`_rxtx`及内部event信号，没有经过AXI/Wishbone/CSRBank；PLIC事务测试给出明确的Wishbone sel或使用完整64位返回，没有经过这个窄CSR组合。因此这些已有测试不具备此次错误的可观测路径。此前整机ISA/golden、综合和routed timing通过也不能补成CSR启动覆盖；新定向组进入普通unittest发现路径，保持真实失败以防后续仅看旧测试PASS。

## 执行与来源

最终测试源 `0b4e18b20aa2cb62e4edab08c8e6c4077842f9a1`，主机Alan，cwd `/home/chen/FUN/flow-be2-csr-tests-20261012`，独立detached worktree、干净。命令：

```sh
BE2_BRIDGE_EVIDENCE=/home/chen/FUN/flow-runs/be2-csr-tests-0b4e18b-20261012/observations \
  bash /home/chen/FUN/flow-runs/be2-env.sh taskset -c 6-7 nice -n 10 \
  python -m unittest discover -s sim/litex -p test_mmio_csr_bridge.py -v
```

Python3.10.19、Migen `e64d0bc12bff644e746d2ddb0d26b3e682b12064`、LiteX `6d8a38cade2092cb1e7db3e5602e81093aead8f9`，实际editable模块路径及6个相关文件SHA记录于metadata。生产源码diff对硬件源b8e6266的`design/src/main/scala`、`litex_wrapper`、KCU105 target为空；没有改共享LiteX树。仿真是这些生产Migen类的内置模型执行，不是Verilator完整SoC或新的网表验证，不新增物理时序证据。

每次仿真前重新读共享`simulation-host.md`；首选cloud_chen免密SSH均5秒timeout/exit255，Alan Python/模块路径、磁盘17GiB、内存及工作区检查可用。配置代理18897被已有listener占用但HTTPS超时，未终止它；本任务新建loopback18898→WSL本地7897反向HTTP代理，HTTP200后fetch准确SHA，不用bundle、不改共享配置或全局Git配置。该临时端口差异作为本任务同步裁定记录。

首次源b5ab470：11项，5PASS/6FAIL/exit1，其中一项直接Wishbone读strobe计数2来自测试驱动在ACK后额外保持请求一拍；修复驱动边界而不修改期望。源db92471：12项，7PASS/5FAIL/exit1；新增TXFULL不弹RX用例PASS。最终源0b4e18b新增满TXFIFO值区分，13项7PASS/6FAIL/exit1。三轮原日志与逐事务观测均保留，未丢弃第一次失败。最终普通失败集合对应上表六项，没有框架/编译ERROR。

原始证据位于Alan `/home/chen/FUN/flow-runs/be2-csr-tests-{b5ab470,db92471,0b4e18b}-20261012/`；本地归档 `records/be2-csr-tests-20261012/`，包含console、exit、逐项JSON、结果汇总与哈希清单。
