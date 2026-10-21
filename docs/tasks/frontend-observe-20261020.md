# 前端硬件观察：事件合同与实施记录

用户 2026-10-20 授权：先修改代码增加观测，用于决定 BPU、指令预取、stream buffer 与 victim cache 的取舍。基线 `678b6961437b6e0e7a626f2f30c16f697aec6054`。本次不启用预取，不实现功能性 stream/victim cache，不运行 Vivado、烧板或写 SD。

## 结构与软件编号

- 原 HPM 1–20 的功能/编号不变，0 及 21–4095 保留。新事件编号 `0x1000 + index`，完整机器可读表见 [事件表](../frontend-observe-events.json)。
- 初版每核 250 个 48-bit 模加观察计数器；其中 128 个用于 I-cache set miss 分布。后续同日 [Sscofpmf 改动](perf-sampling-20261020.md) 追加 cpu_cycles/retired_instructions，现在共 252 项，原编号不变。观察增量为 32-bit，支持一次多个事件或延迟累加，不截成 Bool。
- 新计数器与原 8 个可编程 HPM 共享事件源；HPM 可选择新 raw 编号。Linux perf DTB 增加旧 11–20 及新 raw 映射。本文初版证据不包含溢出中断或模式过滤；这部分后续实现与证据单列于 Sscofpmf 记录，不能沿用初版结果作其验证。
- `BreezeHpmEvents` 传输观察 delta 与行采样。集群保留既有 HPM 寄存边界，后端本地产生的新事件额外寄存一拍以对齐；PMU 再沿用原 rawEvents 寄存。计数窗口定义在 PMU 接收边界，落后源事件两拍，不改功能流水。

## 机器态 CSR

| 地址 | 功能 |
|---|---|
| `0x7d0` | control：bit0 freeze，bit1 clear 脉冲，bit2 trace enable，31:16 trace skip（每 skip+1 个合格样本存一个）。复位计数运行、trace 关闭 |
| `0x7d1` | 计数器 index，低16位；越界读零 |
| `0x7d2` | 所选48-bit计数器零扩展读出；写忽略 |
| `0x7d3` | trace index：低3位 word，7:3 entry；越界读零 |
| `0x7d4` | trace word 只读，写忽略 |
| `0x7d5` | trace状态：低5位已存条数（最多16），高32位饱和丢弃计数；写忽略 |

control 写入沿不计数/采样；clear 优先清空全组计数器、采样条数、drop 和抽样相位。freeze 后任意顺序读全部 index 得到稳定的同窗快照。control 的 clear 位自清；写 control 时需要同时给出希望保留的 freeze、trace enable 和 skip。

这些地址是 M-mode 自定义 CSR，沿用 CSRFile 的权限、精确提交和 trap 屏蔽。常开 bank 不受 mcountinhibit 影响；原 HPM 仍遵守 mcountinhibit。首次测量前须冻结/清零再开始，读取后结合计数窗口和流水尾部解释；本轮没有为 Linux 新增读取整个 bank 的 SBI 调用。

## 计数口径与归属

### 前后端供给

`active_cycles` 排除 reset/WFI睡眠/stop/fatal。`frontend_issued`、`frontend_backpressure`、`frontend_starved`、`both_blocked` 是 active 周期的四分区。结构可接收判断只使用 EX/写回容量、串行占用、恢复和中断状态，不读无效 ID payload 的寄存器/解码信息；这是结构供给损失估计，不是对未知指令依赖的反事实预测。

starved 主因按 iTLB → refill → 恢复 → other 互斥归类，四项之和等于 starved。后端 FP/LSU/串行/反馈等待是可重叠的原因事件，不能相加作总时间。队列 empty/low(1–2)/high(3–slots-1)/full 仅计 active 周期。

### 分支预测

- 条件方向、JAL、非返回 JALR、return 分开按退休计数。return 定义为合法 JALR rd=x0、rs1=x1/x5、imm=0（压缩指令先展开）。
- 最终方向/目标比较在 EX 产生观察元数据，沿 MEM/PERM/WB 保留，到真实退休计数，trap/replay/kill 不冒充退休。
- 每条指令携带其 F1 原始 BTB taken/target/类型匹配信息，独立于 F2 的方向、直接跳转和 RAS 修正。taken CFI 作为 early_target_missing 分母；early_direction_wrong 只数条件分支。
- F2 target/history 修正分别计数，可同拍都发生。F1 正确 taken 转向不计错误。
- 后端恢复区间从 EX 接受重定向后到正确路径 instruction.valid 重新可用（不等后端接收）；新重定向或全局取消终止旧区间并计 superseded。F2 恢复另从修正后到目标 fetch window 的 F1 正式返回，不能混算两种 latency。

### iTLB/PTW

有效、未取消、规范地址、真正开启 Sv39 的 tag 查询才进 hit/miss；tag命中但权限不符仍可记tag hit，同时记fault。Bare/M-mode单列bypass；PTW fault重送不是一次新tag miss。查询取消与已发walk取消分开。

共享PTW的指令侧 queue（grant前）、service（非Idle且I owner）、PTE请求握手、mem wait（等待PTE返回）分别记录。done计completed（含fault/被取消walk），fault/killed另列。D侧PTW不混入I侧；PTW仍经L1D，不改变任何仲裁/优先级。

### I-cache

`ic_demand_access/miss` 是每个取指 owner 首次通过翻译和PMP/PMA后作出的cache判定，包含推测路径；同owner回填后重查只记 `ic_lookup_retry`。TLB miss后首次获得PA仍是该owner第一次cache判定。原icacheAccess/refill请求事件不改语义。

refill时延从miss分配到有效返回，按owner累加；completed包括被取消/error返回，cancel/error另列，分母必须用completed。实际install覆盖有效way才计valid eviction；fence.i失效单列。I侧L2分类复用真实L2Events对应端口，PTW不冒充I-cache L2请求。RVV dataless Home没有普通L2 tag分类，其分类事件为零。

所有8桶延迟分布上界依次为8、16、32、64、128、256、512、无限，区间左闭右开；refill/recovery时延计数器32-bit饱和，累计bank48-bit自然回绕。行间隔用32-bit cycle差（模2^32），超过该窗口的间隔不用于及时率结论。

### 局部性与采样

- 新物理line首次访问/连续line转换/非连续转换、连续流长度及下一行到达间隔分别计数。只在实际访问line变化时转换，重复同line不制造虚假stream；run末尾未结束属于右删失。
- 独立同步tag镜像只观察真实install和fence，不增加功能cache读口。下一行resident探测遇同set镜像读写或fence记UNKNOWN，不猜hit/miss；不跨物理地址溢出。
- 16项最近逐出line记录，报告最近4/8/16次逐出窗口内的miss重复访问。真实install归还的line移除，fence清空。该指标是覆盖线索，不是完整victim替换模型或速度收益。
- set 0–127逐set miss计数，额外overflow覆盖更大几何，不截断索引。
- 16条有界采样，满后drop且不反压CPU。每条6个64-bit word：cycle、fetch-window PC、VA、PA、satp、flags（bit0 miss，bit1 retry，3:2 privilege）。只采样owner首次cache判定中的新line或miss。PC是该取指窗口地址，不承诺指向跨窗口指令的第一parcel。
- 采样存储与常开计数分开；skip只影响trace。错误路径访问可能被记录，satp/privilege可供离线区分，不能把采样视为退休路径trace。

## 验证矩阵

| 要求 | 检查 |
|---|---|
| 48-bit计数、32-bit增量、freeze/clear、越界读取 | BreezeObserveSpec |
| 有限采样、owner字段、overflow、抽样相位 | BreezeObserveSpec |
| next-line resident、同set碰撞、ghost窗口、restore/fence清除 | BreezeObserveSpec |
| 新raw多位增量及旧非法编号不被放行 | BreezeObserveHpmSpec |
| 原CSR/HPM写覆盖/反压/权限语义 | BreezeCsrPipelineSpec、BreezePrivilegeSpec |
| 取指重查不重复计数、跨页两次walk、Bare不算tag hit | BreezeFrontendPipelineSpec |
| 功能路径/预测元数据/反馈容量保持 | 前端和后端原回归、生产系统程序 |

实际执行：首选 `cloud_chen@47.96.71.231:22` 连接超时，按共享主机配置回退到 Alan（`chen@localhost:2286 via clawbot`）。工作区 `/home/chen/FUN/flow-frontend-observe-20261020-678b696`，日志 `/home/chen/FUN/flow-runs/frontend-observe-20261020-678b696`。工具为 Java 11.0.32.1、sbt 1.9.7、Verilator 5.028、RISC-V GCC 13.2.0、DTC 1.7.0。

在同一份最终硬件源文件快照上，14 个测试套件共 **94/94 项通过，0 failure/error/skip**；其中后端仅运行本次新增的 `observation` 定向用例，不能表述为后端全量回归。系统套件的 7 项测试包含 **63 次 ELF 运行**（Cluster 27、Zve 19、RVV 17），全部通过各自自检。覆盖生产单核 scalar、双核、四核、AXI 反压及原向量兼容性；不是 Linux 启动或板上证据。

软件检查通过：事件表/C 头文件/DT 映射一致性、`dtc` 编译、`rv64im_zicsr_zifencei/lp64` 下头文件调用的 `-Wall -Werror` 交叉编译。本地 `git diff --check` 通过。

证据见 [`records/frontend-observe-20261020`](../../records/frontend-observe-20261020/)：`source-manifest.json` 绑定基线 SHA 与未提交 overlay 的逐文件 SHA256，`regress.sh`/`system.sh`/`software.sh` 保存命令，`evidence.tgz` 包含原始日志、XML、ELF 输入摘要、外部 CVFPU 文件摘要及自动汇总 `result.json`。初次测试编译中的 Driver 作用域错误、初次软件验证漏传基础 DTS 的失败日志均保留；分别修复后重跑对应检查，没有更改 golden、程序输入或 watchdog。远端 CVFPU 为复制的源目录，未声称它拥有可核对的本地 Git HEAD。

以上为观察初轮的执行记录。后续Sscofpmf及R1改动随本次提交交付；100MHz布线时序验收和bitstream结果见 [perf时序R1](perf-timing-r1-20261020.md)。尚未上板。

## 第一轮数据的解释

| 问题 | 读数与口径 |
|---|---|
| 条件分支方向预测准确率 | `1 - conditional_direction_wrong / conditional_retired`；最终预测与 `early_direction_wrong` 分开看 |
| iTLB tag miss 率 | `itlb_tag_miss / (itlb_tag_hit + itlb_tag_miss)`；Bare bypass 不进分母，另看 PTW 排队/服务/内存等待 |
| I-cache demand miss 率 | `ic_demand_miss / ic_demand_access`；回填重查另列，不重复放大 miss 率；包含推测路径 |
| 前端供给损失 | `frontend_starved / active_cycles`，再用互斥 wait 主因定位；与 backpressure、both_blocked 并列，结构估计的限制见上文 |
| 是否值得尝试 prefetch/stream buffer | 对照 sequential/nonsequential、run 长度、next-line gap、next-line 非驻留占比及真实 refill 时延；这些读数只提供机会与及时性线索 |
| 是否值得尝试 victim cache | 查看最近 4/8/16 次逐出窗口的重复 miss、valid eviction 与逐 set miss 集中度；不能直接当作新增缓存后的速度收益 |

所有比率仅在分母非零、同一测量窗口且计数未溢出时计算。此轮不从统计线索直接决定新机制。初版观察改动未包含硬件溢出采样；后续实现与验证见 [Sscofpmf 记录](perf-sampling-20261020.md)。

### 2026-10-20 时序R1采集边界

[perf时序R1](perf-timing-r1-20261020.md)为I-cache观察整包增加一拍采集，保留PC/PA/satp/权限/原时间戳并同时延迟demand/install/fence。前端其他观察、MMU/L2和后端本地统计同步增加一拍，权限标签随行；相对本页初版，生产观察delta到PMU边界额外晚一拍。事件编号与增量口径不变；legacy HPM与可编程cycles/retire别名不随观察流水改变。新增时序和功能验证单列于R1记录。
