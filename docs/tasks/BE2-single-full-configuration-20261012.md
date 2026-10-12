# 资源充足的流水单核配置与软件复用

用户 2026-10-12 暂停四核资源裁剪，优先生成独立、较充足的单核。四核8项FPU候选的未提交改动保存在另一独立工作区，不用于此产品。当前分支 `timing/single-full-20261012`，基于 `99c7e8d` 新流水前端/BE2后端；保留取消与历史payload选择解耦改动，并采用等价循环年龄选择，FPU容量仍32项。配置入口为 `BreezeClusterPresets.single`；参数在编译生成RTL时确定，不能通过运行时软件改变。

| 参数 | 本版值 |
|---|---|
| 平台/时钟 | KCU105 xcku040-ffva1156-2-e / 100 MHz |
| hart/ISA/ABI | 1 / RV64IMAFDC、Zicsr/Zifencei / lp64d |
| 特权/MMU | M/S/U、Sv39、16-bit ASID；完整原MMU |
| iTLB/dTLB | 各8sets×4ways + 4superpage entries |
| upper/middle walk cache | 1×4 / 2×4 |
| PMP | 8个实际条目，保留16个CSR布局、上半硬连OFF |
| L1I/L1D | 各16 KiB，128sets×4ways，32B line |
| L1D并发 | 单MSHR，原hit-under-miss/PTW/probe/dirty/原子协议 |
| L2 | 64 KiB，256sets×8ways，2slots |
| 预测器 | GShare 10-bit / 1024个2-bit PHT；BTB两halfword banks×32sets×2ways=128项 |
| RAS/指令队列 | 8项 / 8项，原0/1/2入队、单项出队 |
| FPU | 完整标量FP32/FP64，32项在途tag表，两项入口、两项结果队列 |
| FPR | 完整32×64-bit架构浮点寄存器，不随tag表大小变化 |
| 长执行/HPM | 原MUL/DIV及BE2接口；8个HPM，原cycle/instret |
| 观察 | tandem退休记录、ILA、总线监控器 |
| 外设/启动 | DDR4、UART115200、CLINT/PLIC、coherent SD DMA；BIOS在bit内，SD放软件镜像 |
| load-use bypass | 保持原false配置 |

单核资源配置独立于四核，不再要求两者缓存和FPU容量相同；ISA/权限、地址映射、SD寄存器ABI、时钟及golden继续严格检查。配置字段 `fpTagEntries` 从cluster传到core/backend/FpUnit并输出到生成marker；LiteX拒绝错误容量的旧RTL。

循环年龄选择将32次动态表访问改为“游标到表尾的第一项，否则全表第一项”；无候选时仍返回原游标。容量、接口、提交先于同拍kill、已提交结果保持、killDrain阻止tag复用以及flags规则均不变，需原FpUnitSpec全部golden复测，不以面积猜测替代结果。

软件候选是Alan `/home/chen/FUN/flow-runs/be2-coremark-gapbs-linux-static-20261012/images/Image`，已包含CoreMark/GAPBS，历史SHA256 `524dfe764cef92e90dfe2634dbbd87e2e2c3ee71ca8052e92415cba7adb74cb7`（交付前重新核对实际字节）。标量ISA、DDR/ROM/CSR/CLINT/PLIC/SD地址及100MHz系统/1MHz timebase保持，因此可复用同一Image/fw_jump.bin；新产品生成自己的BIOS、DTB、SD启动包、最终bit/LTX和hash绑定。兼容性核对、最小仿真、综合、routed timing和上板结果分别记载；不将旧内核原SD busy/width问题标为已经修复。

独立配置文件：`design/src/main/scala/config/BreezeSingleFullConfig.scala`，由 `BreezeClusterPresets.single` 引用；明确选择Linux特权profile。生成CLI仍为 `GenerateBreezeCluster single gshare linux tandem fpga-debug coherent-dma`。

当前状态：配置已本地提交。准备同步精确提交到Alan，生成RTL/BIOS并启动综合；未宣称新综合或新时序通过。四核工作暂停；不烧录板卡。
