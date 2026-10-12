# BE2 CSR 读桥修复与单核调试重建

用户确认采用CSR窗口内按原始地址bit2选择Wishbone读半部的修复，并继续单核调试bit重建及上板验证。2026-10-12源码实现 `426aae12094ff105191014db14cc81df623e4419`；构建源 `f88096e6c20f73dd6b76d9b8e5c17f753c46470f`，Alan独立worktree `/home/chen/FUN/flow-be2-csr-fix-20261012`。本报告的阶段结论持续按实际证据更新；最终bit和板上Linux结果未完成时不视为通过。

## 接口、所有权和范围

新增 `litex_wrapper/flow/mmio_bridge.py:BreezeMmioBridge` 包住现有LiteX `AXILite2Wishbone`，仅在实际CSR窗口`0x12000000+0x10000`的读事务中将Wishbone sel改成bit2=0时`0x0f`、bit2=1时`0xf0`。这恢复了`Wishbone2CSR`的子槽地址与返回数据半部；RDATA仍由原桥暂存，不复制/重新定位返回值。窗口外维持原全64位读选通，写使用原WSTRB，协议桥FSM/仲裁/缓存数量和返回拍数没有人为增加。

| 接口／生命周期 | 所有者和保持规则 |
|---|---|
| 原始AR字节地址 | 核心`L1DMmio`保存请求；既有LiteX桥直到WB ACK才给ARREADY，因此整个WB读取期间地址仍须保持，bit2有效 |
| WB读请求及sel | 原桥驱动cyc/stb/address；新模块只在CSR读且事务active时选择一个32位子槽，不新增请求或取消 |
| CSR读副作用 | 原CSRBank在WB→CSR桥读阶段按唯一子槽触发；反压保持已取得响应，不重复产生读选通 |
| AW/W | 原桥等待独立AW与W，地址/数据/选通仍按原协议；同拍、AW先、W先均测 |
| R/B与异常 | 原桥返回通路及L1D的响应／异常／doneConsume规则保留，未修改CPU提交、精确异常、kill或最老MMIO发起边界 |

CPU包装改为显式暴露 `mmio_wishbone` 给LiteX的periph_buses，原始`mmio_bus`继续连接CPU和ILA；防止外壳再自动插入旧桥。四核、单核、单核RVV及单核调试共用该基类。内存AXI路由、DDR/SRAM/ROM、coherent DMA及缓存几何不变。物理结果会受新SoC网表影响，不能引用旧bit时序作为新bit通过。

CSR地址宽14、数据宽32、paging0x1000使用同一模块常量；KCU105和MulticoreSimSoC在SoC finalize后逐项核对实际origin/size/data_width/alignment，不匹配直接拒绝构建。译码只覆盖实际64KiB CSR区，不把整个16MiB PMA设备窗口强行按32位处理。保留当前CSR单槽读语义；PLIC/CLINT完整64位读取不被改窄，没有从AXI-Lite地址推断不存在的ARSIZE。

## 最小验证

源426aae1，Alan：原13项期望加2项新边界检查，共15/15 PASS、exit0，原6项FAIL全消失。新检查包含CSR窗口首末子槽、窗外相邻地址、CLINT/PLIC地址、WB等待4周期、R反压5周期、配置漂移拒绝。UART真实fixture的enable回读3、RX单字节处理与TX屏蔽后IRQ归0，满TX FIFO读TXFULL=1；这些都是模块证据，尚不替代板上Linux启动。

构建源f88096e：桥接15、CPU wrapper6（含三SD配置）、ILA2、被动monitor1、Migen位宽3，共27/27 PASS、fail/error/skip0、exit0。原13数值／副作用／进展期望未修改，仅切换被测桥到生产新模块；现有wrapper的接口拓扑检查按已批准接入变更更新。三SD配置用生成marker夹具检查公共接入，不冒充分别重新生成/实现了三个bit。

第一次integration运行暴露测试夹具缺少coherentDma字段和合并运行的模块导入路径，errors4/exit1，在构建前被拦住；f88096e补齐合法marker并固定PYTHONPATH，未删除产品侧检查或放宽测试。首次runner调用expected SHA写错，被精确SHA守卫拦截，exit1且未运行仿真/编译；错误调用与integration错误日志均保留。随后准确源通过27项。

## 重建与阶段证据

每次仿真/编译前重新读共享host配置，首选cloud_chen免密SSH5秒timeout255；Alan Python3.10.19、GNU RISC-V GCC13.2.0、Vivado2022.2、磁盘及内存可用。同步使用本任务已有loopback18898到WSL本地7897反向HTTP代理，HTTP200后fetch，未用bundle。CPU6–9/nice10，Vivado maxThreads4；无关CISLC-O3构建及用户GUI保持运行。

`records/be2-csr-fix-20261012/build.sh`在独立worktree检查准确HEAD/clean状态及`design`/CVFPU对旧调试源b8e6266的Git diff为空，然后重跑27项。CPU Chisel未改，复用旧准确生成RTL，先逐项验证129份原文件与冻结snapshot SHA，再复制生成目录；记录`core-rtl-reuse.json`，不宣称本轮重新跑了Chisel生成或新的后端功能全回归。新SoC顶层由修复后的Python重新生成，BIOS重新编译，输入再次冻结。

输出Alan `/home/chen/FUN/flow-runs/be2-single-debug-csrfix-20261012/`。准备审计已通过：129项冻结RTL、普通单核功能配置、BIOS内容与ROM初始化前缀、161probe/1896bit ILA、100MHz、深度4096、输入流水2。生成顶层实际出现CSR范围比较和bit2选择，SoC实际CSR参数检查通过。构建已进入Vivado综合，当前最终timing/bit/板上结果待取得；旧调试bit及原三产品保留。

用户已确认SD卡在KCU105板上。卡上已有CoreMark/GAPBS Linux软件文件和启动地址接口仍适用；新硬件完成后重新绑定这些软件hash，不为桥接修复改动Linux或重写SD。仅当完整setup/hold/pulse与route、SD预算及ILA bus-skew通过，才交付并通过已识别Alan JTAG目标`Digilent/210308A7B107`加载新bit到FPGA；不写配置Flash。随后用匹配LTX抓取并对照新BIOS/Linux符号，确认执行能否离开原UART循环及推进SD/Linux。

BIOS重编后的binary SHA仍为`95fecc2e1138e9879d9beeef572ac8c3fe016471ada8e7e052423571a0b0fa7e`，CSR JSON SHA仍为`222155e34feab77ddf20a6c346fde704269409883485c1f9534312d4d87389a7`，XDC SHA仍为`41abd6e4f78d20dabcda6f1f65c6c9320d767639b9b46e27859867ee70c1558e`；均与旧调试产品逐字节hash相同。新SoC顶层SHA=`e5b43da94a55dcb38cffc2abf1319ed0f0141a65d2fdf7070a97bface6952189`，实际包含修复选通。

原始记录在Alan `/home/chen/FUN/flow-runs/be2-csr-fix-426aae1-20261012/`；目录名426aae1保留首轮测试来源，内部reuse/prepared binding明确记录最终构建源f88096e，不把两者混写。同名本地归档 `records/be2-csr-fix-20261012/`含运行脚本及已完成的定向/准备证据。
