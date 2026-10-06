# 访存第一批 RTL：普通 Load/Store 与 L2 Home 主流程

起点 `71f4988`，分支 `feat/pcie-fase-20260920`。用户授权先实现 RTL 并提交，下一批补对齐 spec 的 directed 小测试。本批未修改冻结的 `L1DCoreIO`、后端、MMU 或既有测试。

冻结清单同步：`v1-mem-plan.md` 已明确 L1D/L2 spec 降为参考文档、不进冻结清单，但 `tools/frozen.json` 仍有两项旧记录。本批只移除这两项，不重录任何散列；其余七份冻结文档及 `L1DCoreIO` 不改。

## 实现范围

- L1D：CPU S0/S1/S2 与独立内部完成流水共享 SRAM；hit/miss、S 升级、无效 way 优先/PLRU victim、写回槽与 dirty 整行读、PS mask、install/tag/PLRU、一次发射 replay、PTW response 路由、refill 错误与 LATE。
- 前进机制：CPU S2 等 MSHR 时旧 MSHR 的安装和回放继续；翻译等待 X/Y 使用保持的 CPU S2/S1 按序重查 dTLB，PTW 不受 CPU kill 丢弃；同 set 修改触发重查，PS E→M 也使 victim 状态快照失效。
- probe：整行 S0 所有权、MSHR/写回/PS 等待、升级中 sharer Inv、tag 更新、dirty 回传；LATE 不压住 probe。
- MMIO/FENCE：阻塞 MMIO 的 Done/Exc 与暂存结果、FENCE 排空。访问拒绝和非对齐检查在 S2，无错误地址回绕。
- L2：分优先级轮转、S2 接收请求、miss/probe 槽分配；EVICT/INSTALL/REPLAY、owner 数据合并、Put 目录/dirty 更新、DMA mask 合并、写缓冲忙重试、probe 缓冲消费与 release、REQ 保持和协议断言。
- 内存引擎：保留反压的读选择，检查 RLAST 拍数，写回错误计数并报告。

## 实现取舍与剩余范围

内部完成流水只承接本地任务，CPU S1/S2 始终与后端保持关系一致。非整行内部任务一次一笔；PTW 暂时只在 miss/写回/PS 资源空闲时接受，保留其结果容量。此保守实现及 X/Y 存储方式已同步到 L1D spec，不增加后端接口字段。

LR/SC reservation、80 拍窗口、AMO RMW、aq/rl 未实现；这些请求不能作为当前功能验收对象。完整断言覆盖、事件细化、directed 功能测试、黄金内存/SWMR/目录监视器、多核随机压力与 litmus 待下一批。第一批代码不代表完整一致性或无死锁结论。

## 验证边界

本地只进行差异/冻结文件静态检查。编译与既有 `MemSkeletonElabSpec` 门槛使用 Alan 上匹配提交执行；结果以该 SHA 对应的命令、日志与退出码为准。新 directed 测试及功能仿真本批未运行，综合、时序、FPGA 与软件运行均未运行。

已取得的生成证据：`7f3ac81` 在 Alan 上生产 Scala 98 文件、测试 Scala 62 文件编译成功；补齐 CVFPU 后 `testOnly flow.memsys.MemSkeletonElabSpec` 37/37 通过、exit=0，覆盖五种几何以及集群/外壳/桥接。该轮最初因独立 worktree 的 CVFPU 未初始化而 suite aborted（0 tests）；未计为 RTL 测试失败，也未隐藏该日志。

证据目录：Alan `/home/chen/FUN/flow-runs/20261006-mem-rtl-109d3a7/`，其中 `sha.txt` 为实际被测提交 `7f3ac81`，`elab.log` 为缺依赖轮，`elab-with-cvfpu.log` 与 `retry-exit_code.txt` 为 37/37 轮。GitHub 拉取发生 TLS/连接失败，使用 Git bundle 验证 prerequisite 后同步确切提交；CVFPU 使用 Alan 已有干净副本 `1b220f3`，nested submodule 版本另存于 `cvfpu-submodules.txt`。

随后修正生成提示的轮转索引位宽与单路索引，并补 RSPdown 权限/opcode 检查。最终提交需重新运行相同生成门槛，单独保存 `final-sha.txt`、`final-elab.log` 与 `final-exit_code.txt`；上述 `7f3ac81` 的通过结果不能代替最终提交的结果。
