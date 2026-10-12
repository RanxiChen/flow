# 单核 Linux CoreMark：首次板上用户回传结果

2026-10-12，用户确认 Linux 已进入 shell，执行 `./coremark`，回传完整输出。本次运行报告 `Correct operation validated`，300次迭代用时12.232秒，分数24.525834 iterations/s。原始回传保存于 `records/be2-coremark-gapbs-20261012/runtime/coremark-user-serial.log`，机器可读记录在同目录 `result.json`。这是用户提供的板上结果，不是agent独立抓取的本次串口记录；退出码、运行目录、运行中ELF哈希及精确开始时间没有回传。

## 数值和校验

| 项目 | 结果 |
|---|---:|
| CoreMark | 24.525834 |
| 配置主频 | 100MHz |
| 按配置主频换算 | 0.24525834 CoreMark/MHz |
| 平均每次迭代时间 | 40.773333ms |
| 按经过时间与配置主频估算 | 4,077,333周期/迭代 |

最后一项是elapsed-time等效周期，不是读取cycle/instret后的用户态执行周期，也不是CPI或IPC。`Total ticks=12232`是该port计时单位；由日志对应12.232秒，不能当作CPU cycle。

seedcrc=e9f5，crclist=e714，crcmatrix=1fd7，crcstate=8e3a，crcfinal=5275。前三项算法CRC及seedcrc与官方2K性能运行示例相符；最终CRC与迭代数有关，不要求等于其他迭代数示例的crcfinal。`CoreMark Size=666`对应三算法分配后的尺寸，官方2K示例同样显示666，不表示把标准总数据量降成666字节。

12.232秒满足官方至少10秒规则。本次性能种子通过，尚未回传第二组验证种子 `0x3415,0x3415,0x66`，不能据此声称已经完成官方全部报告要求或获得认证。编译信息为GCC13.3.0、`-O3 -march=rv64imafdc -mabi=lp64d -static -lrt`、Heap，单上下文；Memory location文本仍为上游占位字符串，应在分析记录中补充系统实际分配位置，不能默认为TCM。

## 性能水平和诊断边界

0.2453 CoreMark/MHz偏低。Arm Cortex-M0官方datasheet给出2.25 CoreMark/MHz，约为此次归一化值的9.17倍；只作量级参考，编译器、ISA、存储系统与OS条件不同，不是相同条件下的硬件对比，也不代表所有M0产品的实测值。

这证明该单核能够运行Linux用户态、执行CoreMark并通过本次自检。该基准没有覆盖四核、RVV、GAPBS或全ISA，也没有消除Linux原生日志中的SD初始化故障。根文件系统已经内嵌Image并驻留RAM，所以进入Linux并运行CoreMark，与Linux SD驱动初始化失败可以同时成立；不能将SD错误泛称为Linux Image损坏。

性能瓶颈尚未测定：先核对Linux计时与外部时间、配置频率，再测benchmark期间cycle/instret、取指和访存等待、分支恢复及IRQ开销。SD初始化错误可能带来重试／中断干扰，但尚无本次运行的中断统计；不能直接归因为SD，也不能归因于ILA/Tandem。现有设计中ILA是被动采集，不能无证据地把约9倍差距解释为加了调试。

建议下一步以同一二进制保留多次性能种子日志，并补第二组验证种子；不改变算法、golden、seed或规模以追求分数。再在CoreMark活跃计算阶段抓取ILA或读性能计数器，将退休率与实际等待对应起来。GAPBS运行情况另记。

## 产品绑定与证据范围

上下文硬件为CSR修复版普通单核SD/Tandem/ILA，构建源f88096e6c20f73dd6b76d9b8e5c17f753c46470f，binding中clock_hz=100000000。匹配bit SHA256 `d098ceec08b8bfb25688c35d5828deb2c572696ad22d89ad5950d25df5a6a872`；Linux Image SHA256 `524dfe764cef92e90dfe2634dbbd87e2e2c3ee71ca8052e92415cba7adb74cb7`，包含CoreMark的预期ELF SHA256 `f35d2eab5beafdcc2c7ad3ba4ab3189eaa1f3523ba29073df18165c179994be2`。这些是交付产品哈希，用户此次 `./coremark` 没有附ELF哈希，不冒充运行时重新核验。编译信息与交付版本吻合。

来源：[EEMBC CoreMark v1.01运行和报告规则](https://github.com/eembc/coremark/blob/v1.01/README.md)，[Arm Cortex-M0 datasheet](https://developer.arm.com/-/media/Arm%20Developer%20Community/PDF/Processor%20Datasheets/Arm_Cortex-M0_Processor_Datasheet.pdf)。
