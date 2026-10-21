# Breeze Sscofpmf 与 perf 事件采样

用户授权：2026-10-20，在前端观察事件基础上同时补齐硬件事件驱动的 perf record。权威行为为 RISC-V Sscofpmf 1.0；软件兼容目标是现有 OpenSBI 1.9 / Linux 6.18.7 SBI PMU。参考：

- https://docs.riscv.org/reference/isa/v20260120/priv/sscofpmf.html
- https://github.com/riscv-software-src/opensbi/tree/v1.9
- https://github.com/torvalds/linux/blob/v6.18/drivers/perf/riscv_pmu_sbi.c

基线 HEAD 为 `678b6961437b6e0e7a626f2f30c16f697aec6054`，叠加已验证、尚未提交的 frontend-observe 改动。恢复边界见 `records/perf-sampling-20261020/before-tracked.patch` 与 `before-status.txt`；不覆盖其他工作。

## 行为合同

- 8 个 64-bit HPM 支持硬件回绕、每计数器 OF sticky、MINH/SINH/UINH。无 H 模式，VSINH/VUINH 为零。M-only profile 的 SINH/UINH 也为零。事件编号与控制字段分开 WARL 校验；非法事件不截断成其他合法事件。
- 计数器保留原事件寄存与 pending 累加。以软件可见计数值的实际硬件增量检测进位。写 counter 覆盖 pending 与写入沿事件，不制造溢出；inhibit 保持原 occurrence-time 语义。OF=1 不停计数，只阻止同一 counter 产生新的中断请求。
- 硬件溢出设置 OF；旧 OF=0 时产生一次请求。OF 软件可写。硬件溢出与 selector 写相遇时，硬件 OF set 优先；counter 写则不溢出。禁止把所有 OF 的 OR 当作电平 pending：软件预置 OF=1 是禁用中断，不应自行置 pending。
- 本地 LCOFIP 为独立 sticky pending，硬件新请求 set 优先于同沿软件 clear；不因 OF 软件写或 counter 软件写自动置位。mip 可读写 bit13，sip 仅在委派后可写。OF 不清不能产生下一次请求；清 pending 不清 OF。
- mie/sie bit13、mideleg bit13、M/S trap、WFI wake 接入现有路径。保留现有标准中断优先级，在同目标中令 LCOFI 低于 SEI/SSI/STI；M 目标优先于 S 目标。没有 PLIC 或外部管脚改动。
- Linux profile 实现只读 scountovf（0xda0），bit3..10 反映 OF；M 全可见，S 受 mcounteren 对应 bit 过滤，U 无权访问。未实现 counter 的 OF 为零。M-only profile 不增加 S CSR。
- privilege 与事件来源的实际寄存边界一起传递：cache 与 observation 流用来源标签，后端即时事件和可编程 cycles/retire 用当前架构 privilege，再与 PMU raw event/config 一起寄存。过滤代表事件发生周期的 CPU 模式，不是把推测事件强行归于退休进程。
- 新增可编程 cpu_cycles、retired_instructions 事件，追加到观察 ABI，不移动旧编号。标准 cycles/instructions 映射允许 HPM3..10，使采样不依赖不产生 Sscofpmf 中断的固定 counter0/2。常开 48-bit bank 不承担中断或权限过滤。
- raw 绑定采用互不重叠的掩码区间：12 行覆盖 1..20 与 0x1000..0x10fb，另有2行generic绑定。全部合法编号各匹配一次，非法空洞和高位别名都不匹配。OpenSBI 1.9 的内部表容量为256（实际添加限制255），不能把全部272个raw编号逐行展开。
- 仅在上述能力已实现的 perf 配置声明 sscofpmf/zicntr/zihpm。优先复用上游 OpenSBI/Linux；不虚报其他扩展，不实现 Smcntrpmf 或 H 模式。
- 采样 PC 为中断现场，允许 skid；不是精确 miss PC。地址局部性 trace 继续独立提供证据。raw latency-sum 事件按权重累加，采样周期单位同事件增量，不伪称事件次数。

## 验证追踪

| ID | 刺激与独立期望 | 映射 | 状态 |
|---|---|---|---|
| P01 | 临界 counter + 多位 delta 回绕，OF 一次置位，请求一次；写入临界值本身不触发 | PMU 定向测试 | 模块通过 |
| P02 | 软件预置 OF=1、clear、rearm、多 counter 同沿溢出；OF 与 pending 分离 | PMU/CSR 定向测试 | 模块通过 |
| P03 | M/S/U 切换与寄存中的事件相遇，模式过滤按来源；inhibit/write 语义保留 | PMU 定向、原 HPM 模型回归 | 模块通过 |
| P04 | scountovf M/S/U 访问、mcounteren 过滤与只读检查 | CSR 权限测试 | 模块通过 |
| P05 | M/S 委派、sie/sip 掩码、优先级、vectored trap、WFI，clear/set 同沿 | CSR/整机程序 | CSR及真实固件WFI通过 |
| P06 | 生产 scalar 取指、真实退休、重复中断/重装返回；AXI 反压 | 自检 ELF，真实 BreezeCluster | 真实OpenSBI自检通过；原系统反压回归通过 |
| P07 | OpenSBI 探测/映射、内核中断注册、连续采样、perf.data/report | 固件与 Linux 软件核对/运行 | 真实固件通过；QEMU软件参考通过；Linux-on-RTL/上板未执行 |
| P08 | 原 scalar/多核/RVV 功能与前端观察语义 | 相关系统与模块回归 | 63次原ELF全部通过；CSR/RegFile补充8项通过 |

软件与 RTL 编译、模块仿真、固件运行、Linux perf 运行、综合/布线和上板分别记录，不相互替代。没有硬件执行结果时不能仅因 DT 声明扩展便称采样可用。

## 执行记录（持续更新）

- 实际主机 Alan，首选 cloud_chen 有限超时检查均未连通。主工作区 `/home/chen/FUN/flow-perf-sampling-20261020-678b696`；兼容性回归另用 `/home/chen/FUN/flow-perf-sampling-regress-20261020-678b696`，避免共享 ClusterSim 输出目录。日志根 `/home/chen/FUN/flow-runs/perf-sampling-20261020-678b696`。
- 定向及原 HPM/CSR权限：36项通过；观察/结构/后端观察/生产系统回归：14项通过，其中7项系统测试内包含63次ELF；补充真实suite名 CSRFileSpec/RegFileSpec：8项通过。不能把按文件名指定但不存在的 RegFileCsrFileSpec 计为已执行。
- Linux 6.18.7 + OpenSBI 1.9 + perf 已在独立目录构建。验证配置由生产perf defconfig派生，使用已有完整GCC14.4/musl SDK，去除GAPBS/Python/CoreMark与OpenMP依赖；生产defconfig保留这些原有项目。已启用libelf与frame pointer，并通过BR2_STRIP_EXCLUDE_FILES保留测试工作负载符号。
- QEMU参考验证生成两份非空perf.data，cycles/instructions均有采样，报告包含hot_integer/hot_branch；qemu-result.json明确只证明软件参考路径，不证明Breeze原始事件或硬件时序。
- 真实 BreezeCluster 单核 scalar 运行未修改的 OpenSBI 1.9 与 S-mode 自检负载：6,486,240 周期、4,823,022 次退休，四个 PASS 标记齐全，test exit=0。cycles、instructions、raw I-cache demand miss 各完成6次中断采样/停止/重装；检查scause/scountovf、WFI唤醒、热点PC范围与无残留pending。OpenSBI源目录495个文件逐一匹配官方1.9归档。中断入口次数另由提交日志独立审计。
- 本轮合计15个suite、59项测试通过，另含63次原系统ELF执行及1次真实固件ELF。官方 `GenerateBreezeCluster single` 生成通过，profile明确 `nCores=1`、`rvv=false`、Linux权限、debug=false。生成包提供可迁移filelist与CVFPU依赖。
- 失败/修正记录完整保留：自检C内联汇编重复标签（改为显式ASM函数范围）；裸机链接器不支持PIE（改用现有Linux交叉工具链构建OpenSBI）；旧SDK缺libc.a、另一SDK不含OpenMP（选完整SDK并独立精简验证配置）；首次符号保留配置名称无效（改用实际支持的strip排除配置）；初始展开映射超过OpenSBI表容量（等价掩码分组）；首次2M周期仍在libfdt初始化到限（预算改10M并打开提交日志，后续确认初始化约6.4M周期）；S-mode自检把配置CLEAR_VALUE位误写成SKIP_MATCH，SBI返回EINVAL（自检改为从官方头文件生成ABI常量）。没有更改原程序golden、seed或watchdog来掩盖RTL失败。

## 交付与边界

本地证据索引：[result.json](../../records/perf-sampling-20261020/result.json)、[source-manifest.json](../../records/perf-sampling-20261020/source-manifest.json)、[evidence-archive.json](../../records/perf-sampling-20261020/evidence-archive.json)。最终源码归档含未提交overlay及逐文件摘要；与执行时源码比较时，仅文档和自检fixture的一处映射行数注释更新，硬件及自检逻辑不变。原始XML、命令、exit、工具版本、真实ELF/DT、软件perf.data与摘要随归档保存。体积大的逐拍日志与失败尝试留在Alan，归档给出路径、大小和SHA256，不删除失败证据。

Alan日志根下 `boot/` 提供内核、OpenSBI、perf DTB、串口handoff和boot.json；`artifacts/rtl-single-scalar.tgz` 提供已生成单核无向量RTL。启动镜像采用上文独立精简验证配置，不能称为包含全部GAPBS等软件的生产defconfig构建。

以上为采样初轮交付时的记录。后续R1已完成同配置100MHz布线时序验收和bitstream生成，并复验真实OpenSBI采样；最终结果及来源见 [perf时序R1](perf-timing-r1-20261020.md)。源码随本次提交交付。Linux-on-Breeze和板上perf record尚未执行。使用说明见 [linux/sd-perf/README.md](../../linux/sd-perf/README.md)。
