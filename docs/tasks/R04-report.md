# R04：预取与数据通路流水报告

分支 `feat/rvv-20261005`，起点 `1789ddd`。第 0 步 `fcd4d4d` 已提交并 push 设计文档和任务书。工作区 `/home/chen/leisure/flow-rvv`；未跟踪的 `AGENTS.md` 保留。本文随实际验证更新，未完成项不记为通过。

## 实现决定

- 已提交 VIQ 32 项，未提交暂存区 `min(8, viqDepth)`；两个分布式 FIFO 分别服务分派与请求游标。commit 同时写两者，容量预留取两游标剩余项数的最大值。请求游标消费非访存项，但只接受已提交的 Ok 访存，串行项仍留在未提交区。
- 默认记分板 16 项、单元队列 4/2/2/2/2、访存描述 16 项、区间表 16 项、翻译 tag 8 个，返回缓冲 16 KiB、4 beat/burst、单 AXI ID。VIQ 增大用于真实请求预取；没有增加记分板和乘加等待队列来代替预取。
- VLSU 先按请求游标分配描述符，load 可发 AR，返回数据先留缓冲；分派后按完整模年龄号唯一绑定记分板 slot。store 可取得描述符，但绑定前不发 AW/读 VRF/失效，且其 W 请求发完前不开始下一条访存。重叠检查继续使用原区间表，store 区间直到所有 B 响应后才释放。
- 两个游标、翻译 tag、区间、记分板和 VLSU live 描述符均参与模年龄租约。当前默认年龄号 8 位；活跃差值限制小于半空间。编号不会仅按低位索引绑定，绑定时断言只有一个匹配描述符。
- VRF 的每个 BRAM 副本读出先寄存，再作 bank 选择。每个写 bank 在仲裁后寄存 data/enables/address，下一拍物理写；写回进度和被动生产者完成事件相应延迟一拍，避免在物理写之前解除冒险。
- dot 是跨指令流水：读地址、BRAM/读出寄存、操作数寄存、乘积寄存、两级加法树、累加寄存、输出 FIFO、bank 写入寄存。单元输出先预留容量，固定流水各级自由推进，输出反压不进入 DSP 宽保持网。累加旁路按完整 VRF row/年龄标记，其他单元写回使旧旁路失效。已有权重 RAW、跨单元 RAW/WAR/WAW 和 v0 依赖仍由记分板检查。
- ALU 同样跨指令流水，按程序顺序读/写，使用单元内部旁路。部分写的目的旧值通过额外执行读口取得，以保留 tail/mask undisturbed 字节；部分行因此可能需要 3 个执行读口。旁路数据为异步分布式存储，valid/age 为寄存器，数据阵列不复位；它们不是 VRF，VRF 本身仍要求 LUTRAM=0。
- 普通 vmacc 保留单指令 sequencer，乘积与累加分级；SEW64 保留逐元素路径，拆开乘积与加法。slow 操作数按 64 bit 分片使用保留的局部使能。未新增 FP 或串行执行功能，无形式化。

## 执行与证据

仿真/编译/RTL 生成每次启动前重新读取 `/home/chen/leisure/flow/docs/cross-project/simulation-host.md`，SSH/环境/工具/内存/磁盘预检后使用 cloud_chen。独立 clone `/home/cloud_chen/work/flow-rvv-r04-20261007`，证据根 `/home/cloud_chen/evidence/rvv-r04`。Vivado 在 Alan，证据根 `/home/chen/FUN/flow-r04-evidence`。nice10，单构建/仿真最多 4 个核；同主机并发总量不超过半数核。工具为工程 sbt1.9.7、Java11、Verilator5.028、Vivado2022.2。

云端安装的 Spike 导出包缺少 `insn_macros.h`，保留 `a2/c2/build-reference.log` 失败。随后复制 Alan 干净源码 `76ce016b6765d66c93522b0ea9a16a44841cb331` 与原二进制（SHA256 `c0a8eb834cc94e92afb372f29bd7d2a87215c5fb6ee0dc19ed84792e64222c2a`）的仅去调试符号副本。云端运行副本 SHA256 `4e0424afa1234a304d9620dde8b1027be0993c63c0d1038c774d4b429138c01e`，插件在云端从当前仓库重新编译。未改变参考指令语义。复制包和哈希留证据根。

### 已完成候选

| 候选 | 源 SHA | 主机/证据子目录 | 结果 |
| --- | --- | --- | --- |
| 初版流水 | `8be6515` | cloud_chen `a1` | Scala 编译通过；RTL 生成因 cross hazard 新字段未初始化失败，后修复 |
| 初版流水修复 | `1cf6c3c` | cloud_chen `a2/c1` | C1 3/3；C2 参考工具缺头文件，未形成 DUT 功能通过证据 |
| 预取 | `5545fa3` | cloud_chen `b1/c1` | C1 3/3 |
| 完整 dot/预取 | `733aa3a` | cloud_chen `pipeline`，Alan `pipeline/s1` | C1 3/3；前端8/8、VRF1/1。dot 负数符号扩展失败，已修复；既有 C2 drain 失败，已修复通知端口冲突。S1 见下 |
| 符号/诊断修复 | `2e2a65d` | cloud_chen `fix` | 两个预取压力用例与 Spike 一致；P6 67 拍失败（初始化标量 move 未结束导致前三个行读等待，测试准备后改为所有操作数在 VRF 后起算）。P1/P2/P3 54.303%/53.333%/51.702%，旧 ALU 串行路径瓶颈；新口径 P4 最大1拍 |

上述 P6 准备修正只改变新用例的初始化屏障：所有初始寄存器 load drain 后发首 dot，不增加首读与末读之间的允许拍数，合同仍 ≤66。

`cache/c1` 的 SHA 记录与同步发生交叠，记录为 `6665500`，实际编译时源码已到 `5618e9c`；该目录不作为有效 SHA 绑定的 C1 验收证据，保留并重新执行。后续同步先确认完成，再启动验证。

## 面积与时序账

| 候选 | LUT | LUTRAM | FF | DSP | RAMB36/18 | S1 WNS | S2 WNS |
| --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| R03 `148c267` | 46719 | 564 | 15599 | 154 | 199/1 | +0.107 ns | -3.350 ns |
| R04 `733aa3a` | 69884 | 958 | 51420 | 154 | 199/1 | +0.986 ns | 正在执行 |

`733aa3a` VRF LUTRAM=0、192 RAMB36。此阶段 frontend15573、sb5782、vrf9306、mem12443、ALU4783、MAC18772、cross52 LUT；frontend/sb/vrf 单项超预算，面积优化后需重新测量。总量低于77k不代表预算逐项通过。

S1 最差路径从 `mem/desc_15_length_0_reg[4]/C` 到 `mem/bursts/ram_ext/Memory_reg_0_63_0_6/RAMA/I`，23级，data8.930ns=logic3.211+route5.719ns；最差10路径原始 `worst-paths.rpt` 留 Alan。S2 继续默认 `opt_design/place_design/route_design`，10ns，无时序例外或策略改变。

## 正确性与测试追踪

- 信用按程序顺序获得，最老返回 beat 只能等待它自己的分派/寄存器冒险；这些旧指令不依赖更年轻的预取数据。小缓冲达到信用上限时停发 AR，R 始终 ready，释放仅在该 beat 所有 piece 写回后发生。新增 512B/80拍用例检验实际前进与最终 Spike 状态。
- 请求游标遇到 store 后 VLSU `generating` 保持到最后 W；更年轻请求不得越过。原 testbench 对每个 AR/AW 检查程序顺序、物理页切分以及更老重叠访问 R/B 完成。新增非对齐 store→重叠 load 同时覆盖 store barrier 与 B 等待。
- 预取绑定前不能写 VRF；绑定后仍检查 WAR/WAW。新增 load 的返回早于更老 dot 末读的事件检查，并比对全部最终寄存器 dump。
- 回绕用例包含 272 对 load/dot，数量超过完整年龄号空间，沿用模年龄窗口与唯一绑定断言。原 kill 后迟到翻译 tag 回绕测试保留。
- 零长度访存与 store 共用 progress0。原候选在有 store gather/B 完成的沿同时释放零长度描述符，导致零长度 scoreboard 完成通知被覆盖；现只在该端口空闲时退休该零长度项，不能先释放再补通知。
- 既有定向、随机、黄金预期、timeout 和断言未删除或放宽。VRF 原写一沿/读一沿采样调整为写两沿/读两沿，全部数值与同址断言保留；直接 scoreboard 仍一沿更新掩码。新旁路输入在原直接 scoreboard 测试中置零，继续验证原路径。

## 链接测量口径

`macCandidate/macReady/macBlocking` 只输出观察数据，不进入控制。macReady 表示消费者已是 sequencer 当前首行、其他 RAW、WAR/WAW、执行读口与输出信用均满足，排除的仅是被配对的源寄存器 RAW。被动 driver 逐拍保存阻塞原因，并取首次满足拍为 `consumerExceptRAWReady`，与实际物理生产者完成拍求 max，再减实际源首读拍。原 `*-pairs.json`、旧 CSV 字段保留；新增 `r04-links-*.csv` 和逐拍 `r04-blocking-*.csv`。

## 最终验收与限制

正在验证优化候选；C1、C2、C3、P1–P7、S1/S2 将分别写入实际结果，不将脚本 exit0 当作性能/时序阈值通过，也不把历史候选的通过转移到新 SHA。
