# Breeze MMU 闭环检查清单

MMU 实现时**假设**下列能力由其他模块提供，MMU 本身不实现、不验证。等前端、LSU、D-cache、核心集成全部完成，做系统闭环时，逐项检查本表。每项检查完写明证据（测试名、波形、提交号）。

| # | 假设 | 负责模块 | 检查方法 | 状态 |
| --- | --- | --- | --- | --- |
| C1 | **跨页取指**：32 位指令跨 4 KiB 页时（前半在页尾 2 字节，后半在下一页），前端对两页分别做 iTLB 查询；两页任一 fault 都按该指令的 PC 报 instruction page fault，`stval` 为出错那半所在的地址 | 前端（重构时实现） | 定向测试：指令跨页，第二页未映射 / 无 X / U 位不匹配；在启用 C 扩展时再测一遍 | 未检查 |
| C2 | 数据非对齐访问在 LSU 送 dTLB 之前直接 trap（misaligned exception），dTLB 不会收到跨页数据访问 | LSU | 非对齐 load/store/AMO 定向测试；dTLB 断言 4 不触发 | 未检查 |
| C3 | 写 `satp`、`mstatus`（SUM/MXR/MPRV/MPP）、特权级切换、xRET 之后冲刷流水 | 核心 | 写 CSR 后紧跟访存，检查使用的是新值 | 未检查 |
| C4 | `sfence.vma` 串行执行：前端 kill → store buffer 排空 → 等 MMU `idle` → 发 sfence → 等 `idle` → 重新取指 | 核心 | MMU 断言 6/7/8 不触发；改页表 + sfence + 访问的定向测试 | 未检查 |
| C5 | sfence 前写页表的 store 已进入 D-cache，PTW 通道读到新值 | 核心 + D-cache | 同 C4 | 未检查 |
| C6 | PTW 通道：`req` 接收后必定返回一个 `resp`；按 S 模式做 PMP/PMA，失败返回 `accessFault` | D-cache | D-cache 侧测试；PTE 位于 PMP 禁止区的定向测试 | 未检查 |
| C7 | 前进保证：TLB miss 的请求不占用 D-cache 资源；PTW 通道有保留的 miss 资源或最高优先级 | D-cache | 普通访存把 MSHR 占满时并发 TLB miss 的压力测试 | 未检查 |
| C8 | 最终 PA 的 PMP/PMA 检查（D 侧在 tag match 之后，I 侧在前端/I-cache） | D-cache、前端 | PMP 定向测试，含 MPRV | 未检查 |
| C9 | 请求方收到 `miss` 后按程序顺序重发该请求及其后被丢弃的请求 | 前端、LSU | MMU 测试 T14 对应的系统级场景 | 未检查 |
| C10 | 设备树声明 `svade`，`satp.ASID` 实现 16 位且 WARL 读回正确；Linux 能检测到 ASID | 软件/核心 | 启动 Linux，检查 ASID 分配是否启用 | 未检查 |
| C11 | 异常码映射：请求方按自己的访问类型把 `pageFault/accessFault` 转成 12/13/15、1/5/7；`stval` 为出错的 VA | 前端、LSU | riscv-tests 虚拟内存相关用例 | 未检查 |
