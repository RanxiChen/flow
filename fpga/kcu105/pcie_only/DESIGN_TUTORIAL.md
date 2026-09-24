# PCIe-only r1 设计讲义与教学交接

本文面向需要从代码讲解该设计的工程师或agent。讲解时必须区分：

- **代码事实**：仓库中已经实现的结构；
- **工具证据**：仿真、综合、实现、时序和CDC报告；
- **板级证据**：烧录后ILA、`lspci`和链路状态。

不得把“生成了bitstream”说成“PCIe已经可用”，也不得把旧r3的板级结果
转移到本r1位流。

## 1. 任务为什么要拆出PCIe-only

原PCIe/FASE位流同时包含：

1. Xilinx XDMA PCIe核；
2. AXI Clock Converter；
3. AXI到Wishbone桥；
4. Breeze CPU、L2和DDR；
5. FASE PCIe控制BAR；
6. FASE JTAG mailbox和仲裁器；
7. PCIe状态参与SoC PLL复位的复位监督器。

当Host上看不到`10ee:9038`且Root Port宽度为0时，这些后级逻辑大部分尚未参与。
继续同时观察CPU、UART或FASE会混淆因果关系。r1采用控制变量法，只保留PCIe链路
形成所必需的部分。

本阶段的问题定义是：

> Host是否向KCU105提供有效REFCLK和PERST#，XDMA LTSSM是否开始训练并到达L0？

## 2. r1边界

```text
KCU105 PCIe connector
  |- 100 MHz REFCLK differential pair
  |- PERST#
  `- RX/TX lanes x8
             |
             v
       Xilinx XDMA 4.1
       Gen3 x8, 10ee:9038
          |           |
          |           +-- AXI4-Lite DECERR slave
          +-------------- AXI4 DECERR slave
          |
          +-- PCIe debug outputs --> link ILA (axi_aclk)

Board 125 MHz --> boot ILA
                    ^
                    `-- synchronized PERST/status and clock heartbeats

JTAG --> Vivado debug hub --> both ILAs
```

r1明确不包含Breeze、FASE、Wishbone、DDR、SD、SoC MMCM或AXI Clock Converter。
JTAG只读取Vivado ILA，不存在自定义USER JTAG命令协议。

## 3. 代码阅读顺序

建议按以下顺序讲解：

1. `README.md`：目标、验收边界与构建入口；
2. `constraints/kcu105_pcie_only.xdc`：真实板级引脚和输入时钟；
3. `create_project.tcl`：器件、XDMA配置、ILA配置和报告生成；
4. `rtl/PcieOnlyTop.sv`：时钟、复位、CDC、XDMA和ILA接线；
5. `rtl/PcieAxiDecerr.sv`：为何未使用的AXI接口仍需遵守协议；
6. `sim/decerr_tb.sv`：握手级定向验证；
7. 构建目录中的`timing_summary.rpt`、`cdc.rpt`、`drc.rpt`；
8. 板级ILA导出和Host端`lspci`证据。

`capture/program_and_arm.tcl`只完成烧录和ILA布阵，不控制Host电源；
`capture/export_capture.tcl`在事件后导出CSV和ILA原始数据。

## 4. 谁提供什么能力

| 层次 | 实现来源 | 职责 |
| --- | --- | --- |
| PCIe PHY、Data Link、Transaction、LTSSM | Xilinx XDMA 4.1 | 完成PCIe协议与DMA引擎 |
| 片上逻辑分析 | Xilinx ILA 6.2 | 通过JTAG采集内部信号 |
| PCIe差分参考时钟缓冲 | `IBUFDS_GTE3` | 向GT和XDMA提供参考时钟 |
| 板载125 MHz缓冲 | `IBUFDS` + `BUFG` | 提供独立观察时钟 |
| 单bit CDC | `xpm_cdc_single` | 同步状态和heartbeat |
| AXI安全终端 | 自研SystemVerilog | 对意外访问返回DECERR |
| 工程生成和实现 | Vivado Tcl | 生成IP、综合、实现、报告和bitstream |

LiteX/Migen没有进入r1运行设计。旧工程中的`pcie.py`是XDMA与SoC的集成胶水，
不是PCIe协议核。

## 5. 时钟域

### 5.1 PCIe REFCLK

Host通过PCIe插槽提供100 MHz差分参考时钟。`IBUFDS_GTE3`产生：

- `pcie_refclk_gt`：送入GT专用时钟端口；
- `pcie_refclk`：`ODIV2`专用时钟输出，直接送入XDMA的`sys_clk`；
- `pcie_refclk_fabric`：`ODIV2`经`BUFG_GT`后的fabric分支，只驱动heartbeat计数器。

GT时钟输出不能作为普通布线直接驱动slice里的计数器。初版实现日志正是通过
一条`ODIV2 -> counter` open net暴露这个问题，因此观测分支显式加了`BUFG_GT`。

如果Host没有提供REFCLK，`refclk_heartbeat_counter`不会变化；125 MHz域看到的
`refclk_seen`保持0。

### 5.2 XDMA `axi_aclk`

`axi_aclk`由XDMA输出。r1中的AXI安全从设备、LTSSM ILA和AXI活动探针都使用该时钟。
如果XDMA没有产生`axi_aclk`，link ILA也无法采样，因此不能只依赖link ILA判断故障。

### 5.3 板载125 MHz

KCU105独立上电后该时钟持续工作，不依赖Host PCIe状态。boot ILA使用它观察：

- PERST#是否出现低、高以及边沿次数；
- REFCLK计数器是否翻转；
- `axi_aclk`计数器是否翻转；
- `axi_aresetn`和`user_lnk_up`的同步状态。

这就是为什么需要两个ILA，而不是把所有探针放在`axi_aclk`域。

### 5.4 JTAG TCK

JTAG TCK由Vivado debug hub内部管理。r1没有用户自定义TCK域RTL，也不把TCK直接送入
功能逻辑。

## 6. CDC设计

CDC不是“用了两个时钟就自动正确”。必须先分类信号语义。

| 源 | 目的 | 信号 | 方法 |
| --- | --- | --- | --- |
| 异步插槽输入 | 125 MHz | `PERST#` | `xpm_cdc_single`三级同步 |
| PCIe REFCLK | 125 MHz | 分频计数器bit | 单bit toggle同步 |
| `axi_aclk` | 125 MHz | 分频计数器bit | 单bit toggle同步 |
| `axi_aclk` | 125 MHz | reset/link状态 | 单bit同步 |
| 异步插槽输入 | `axi_aclk` | `PERST#`观察副本 | 单bit同步 |

`cfg_ltssm_state[5:0]`、协商宽度和速度没有跨到125 MHz域。它们是多bit编码，
逐bit同步可能在状态跳变时形成一个从未真实存在的组合值。因此这些信号留在
`axi_aclk`域，由link ILA原地观察。

这是一条可迁移的CDC规则：

> 单bit电平可用同步器；事件可用toggle/脉冲握手；多bit数据必须用稳定协议、握手、
> 异步FIFO或保持在源时钟域观察，不能逐bit随意同步。

## 7. 复位路径

`pcie_x8_rst_n`直接连接XDMA的`sys_rst_n`。r1没有`FlowPcieReset`，也没有SoC PLL。

因此：

- r1的PCIe链路是否形成，不受CPU或FASE复位影响；
- `axi_aresetn`由XDMA输出，用于复位两个AXI安全从设备；
- boot ILA对PERST#做独立同步，只用于观测，不反馈控制XDMA；
- 调试逻辑不能改变链路训练条件。

这种“观测路径不反控功能路径”的设计可降低Heisenbug风险。

## 8. 为什么需要AXI DECERR从设备

XDMA配置会输出AXI4和AXI4-Lite主接口。即使r1不验证DMA，也不能把READY、VALID和
RESP任意绑常量。

错误做法示例：

- 永久拉高`AWREADY/WREADY`但从不返回`BVALID`：写事务永久挂起；
- 接受`AR`后只返回一个R beat：burst读永久挂起；
- 固定`RVALID=1`：没有AR请求也凭空产生响应。

`PcieAxiDecerr`的行为：

1. 接受一个AW，保存ID；
2. 持续接收W，直到`WLAST`；
3. 返回同一个ID和`BRESP=DECERR`；
4. 接受AR，保存ID与`ARLEN`；
5. 返回`ARLEN+1`个零数据beat；
6. 每个beat都返回`RRESP=DECERR`，最后一个beat置`RLAST`。

`PcieAxilDecerr`还体现AXI-Lite的重要规则：AW和W是独立通道，地址和数据可以任意
先后到达。模块分别锁存两个握手，二者都完成后才生成B响应。

计数或状态转移必须以`VALID && READY`为准，不能只看VALID。

## 9. ILA探针

### 9.1 boot ILA：`clk125`

| Probe | 含义 |
| --- | --- |
| 0 | 同步后的PERST# |
| 1 | REFCLK heartbeat toggle |
| 2 | `axi_aclk` heartbeat toggle |
| 3 | 同步后的`axi_aresetn` |
| 4 | 同步后的`user_lnk_up` |
| 5 | sticky状态集合 |
| 6 | PERST#上升沿计数 |
| 7 | PERST#下降沿计数 |

Sticky状态用于弥补ILA时间窗口有限的问题。即使事件发生在触发窗口之外，也能知道
该事件是否曾发生。

### 9.2 link ILA：`axi_aclk`

| Probe | 含义 |
| --- | --- |
| 0 | `cfg_ltssm_state[5:0]` |
| 1 | `cfg_negotiated_width[3:0]` |
| 2 | `cfg_current_speed[2:0]` |
| 3 | `user_lnk_up` |
| 4 | `axi_aresetn` |
| 5 | 同步到`axi_aclk`的PERST#副本 |
| 6..8 | correctable/nonfatal/fatal error |
| 9 | local error code |
| 10 | local error valid |
| 11 | AXI/AXI-Lite活动摘要 |

### 9.3 结果分类

| 观测 | 第一判断 |
| --- | --- |
| PERST#一直低 | Host/插槽没有释放复位 |
| PERST#释放但REFCLK heartbeat不动 | 插槽REFCLK缺失或未到FPGA |
| REFCLK活动但`axi_aclk`不动 | XDMA/GT初始化未完成 |
| `axi_aclk`活动，LTSSM停在Detect | 对端或lane检测失败 |
| LTSSM在Polling/Configuration循环 | 链路训练或lane协商失败 |
| LTSSM到L0且link-up为1 | FPGA侧链路建立，继续查Host枚举 |
| L0且Host枚举成功 | r1板级目标达成 |

具体LTSSM编码应以Vivado 2022.2对应的UltraScale PCIe产品指南为准，讲解时不要凭记忆
给十六进制值贴标签。

## 10. 与“额外16字节/+2 word”问题的关系

旧板测中，4 KiB C2H读应产生：

```text
4096 bytes / 32 bytes per AXI beat = 128 AXI beats
128 AXI beats * 4 Wishbone words per beat = 512 64-bit words
```

实际观测到514个64-bit Wishbone word，多出2个word，即16字节。旧源码增加了：

- `ar_requests`
- `ar_beats`
- `ar_narrow`
- `r_beats`

r1不产生有效DMA流量，所以不搬入这四个计数器。r2会加入PCIe-only状态BAR和片上RAM，
在XDMA AXI边界重新统计它们：

- 若`ar_requests=1, ar_beats=128, r_beats=128`，XDMA请求正常；
- 接回AXI-Wishbone桥后若仍出现`read_words=514`，问题在桥或下游；
- 若`ar_requests=2`或`ar_beats=130`，额外访问来自XDMA上游。

这体现“在嫌疑模块边界两侧分别计数”的定位方法。

## 11. 验证阶梯

必须逐层升级证据：

1. `bash sim/run.sh`通过：只证明自研DECERR从设备的定向协议行为；
2. Vivado生成XDMA/ILA成功：证明IP参数和外层端口存在；
3. 综合成功：证明RTL可综合、实例接线成立；
4. 实现与时序通过：证明布局布线后setup/hold满足；
5. CDC/DRC人工分类：证明没有未解释的关键结构问题；
6. 生成bitstream：只证明工具产物存在；
7. FPGA配置成功：证明SRAM配置成功；
8. ILA捕获：证明板上PERST#/时钟/LTSSM实际行为；
9. Host枚举：证明配置空间可见；
10. `LnkSta`：证明实际协商速度和宽度。

r1不验证BAR和DMA。它们属于r2。

## 12. 教学提问建议

另一个agent可按以下问题检查学习效果：

1. 为什么link ILA不能替代boot ILA？
2. 为什么不能把6-bit LTSSM逐bit同步到125 MHz？
3. 为什么未使用的AXI端口仍然要返回完整burst响应？
4. AXI写地址和写数据为什么必须独立接收？
5. `VALID`为1是否等于发生了一次事务？
6. bitstream生成、时序通过和板上枚举分别证明什么？
7. 为什么r1不应包含“+2”计数器？它们应在哪个阶段加入？
8. 如果PERST#高、REFCLK有、`axi_aclk`有，但LTSSM停在Detect，应优先排查哪一层？

## 13. 求职表述边界

在r1完成板测前，可以说：

> 正在搭建KCU105 PCIe Gen3 x8独立bring-up与ILA观测环境，将PERST#、REFCLK、XDMA
> LTSSM和Host枚举分层验证。

只有完成对应证据后，才能逐项增加：

- “完成综合实现与时序/CDC检查”；
- “定位LTSSM状态与链路训练问题”；
- “实现Gen3 x8枚举”；
- “完成BAR与DMA验证”。

不要在r1仅生成bitstream时声称“完成PCIe控制器设计”。PCIe协议核来自Xilinx XDMA；
个人工作应准确描述为系统集成、复位/CDC设计、可观测性、协议安全终端和板级验证。

## 14. 当前证据状态

本节由实际执行者更新，其他agent应先读这里再授课：

- 源码：已创建r1独立目录；
- DECERR定向仿真：本地Icarus通过，标志`PCIE_ONLY_DECERR_PASS`；
- XDMA/ILA生成：Vivado 2022.2通过；
- 综合：修正版`synth_design Complete!`；
- 实现：修正版`write_bitstream Complete!`，路由0 failed nets；
- 时序：post-route WNS `+0.127 ns`，WHS `+0.013 ns`，setup/hold均0 failing endpoints；
- CDC：自定义顶层的6组跨域均被报告为`CDC-3 Info`；`CDC-6/15`告警全在生成的
  ILA/debug-hub层级；
- DRC：0 error、5 warning；配置电压声明告警仍保留，未伪造0 warning；
- 初版实现：DRC正确拒绝`ODIV2`直接驱动fabric计数器；
- 修正版：新增`BUFG_GT`观测分支后完成构建；
- bitstream：已生成，SHA-256为
  `a53f6e2ad7c6a9961dcba05dae47a4a0c4b6eef5156cd19dff73575209b897c1`；
- LTX：已生成，SHA-256为
  `512091e67b6c92ed13a1339e32ac20040e077456a998189ac4ba1529c35504dd`；
- 烧录：未执行；
- 板级ILA：未执行；
- Host枚举：未由r1验证。

完整构建证据和告警分类见`BUILD_EVIDENCE.md`。
