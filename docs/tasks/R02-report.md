# R02 执行报告

## 执行状态

2026-10-07：用户确认原报告的两条缺口已经由设计文档 2.4(2)(3)(8)、3.9 与 R02 2.2、2.4、第 3 节闭合，授权直接实现。取消开工前暂停，不再进行开工前审阅。

- 工作区：`/home/chen/leisure/flow-rvv`
- 分支：`feat/rvv-20261005`
- 第 0 步父提交：`6f959633e8702009d70fc7322a8c955847f3d7b3`
- 第 0 步范围：设计文档、R02 任务书、本报告。
- 顺序：C1 → C2 → C3 → P1–P5 → S1，每段提交、push、更新报告。
- 隔离：只新增 R02 允许路径，不修改共享构建文件或 Breeze；所有构建、仿真、综合在 Alan 的独立工作区运行。
- 局部遗漏记录处理方式与理由后继续；只有必须推翻已定结构时暂停。

## 验收结果

| 合同 | 状态 | 提交、命令与日志 |
| --- | --- | --- |
| C1 | 通过：三组 RTL 生成、Verilator 编译与空闲复位冒烟 3/3 | `3644575`；日志 `3644575-c1` |
| C2 | 通过：前端 6/6、VRF 1/1、Spike 定向/冒险 8/8 | `4c1fc28`；`final-c3/c2-check/` |
| C3 | 通过：种子 0–999，1000/1000 | `4c1fc28`；`final-c3/`，退出码 0 |
| P1 | 通过：100% 峰值 | `d904bd1`；`gemv-performance/` |
| P2 | 通过：97.07% 峰值 | 同上；40 拍、16 KiB |
| P3 | 已测量：89.07% 峰值 | 同上；40 拍、2,560 B |
| P4 | 未达标：最大 4 拍（合同 ≤2） | 同上；全部 224 对统计，补时间线诊断 |
| P5 | 通过：P1 提前 3–70 拍，P2 提前 42–70 拍 | 同上；全部 223 对均提前 |
| S1 | 未运行 | 待性能段；XCKU040 OOC 100 MHz |

## 实现决定与文档处理

- 区间在 Ok 时登记；提交当拍旁路查询；仅匹配已提交项，load 在全部 R 接收后释放、store 在全部 B 后释放。
- kill 撤销被作废项判定；同拍 commit 保留最老项；已握手翻译编号保留到响应被接收并丢弃，禁止提前复用。
- FP、串行执行仅框架；serialGo 触发未实现断言。不做形式化验证，不继续 R01。
- 与冻结设计结构不一致：无。

## 命令与证据

第 0 步提交号与各阶段证据如下；系统集成不属于本任务范围。

### 第 0 步与 C1

- 第 0 步：`dc31215`，指定三份文档已提交并 push 到 `origin/feat/rvv-20261005`。
- C1 RTL：`63fbe8d`，代码已 push；只新增 `design/src/main/scala/rvv/`、`design/src/test/scala/rvv/`、`rvv/r02/`，共享构建文件未修改。
- Alan 独立 clone：`/home/chen/FUN/flow-rvv-r02-20261007`；初次网络 clone 因 TLS 中断失败，改为复制 R01 clone 的 Git 对象到独立 clone，再从 GitHub fetch 最终提交；没有在 R01 或其他任务的工作区切换分支。
- cwd：`/home/chen/FUN/flow-rvv-r02-20261007`。
- 命令：`source /home/chen/miniforge3/etc/profile.d/conda.sh && conda activate flow && bash rvv/r02/run-c1.sh /home/chen/FUN/flow-r02-evidence/63fbe8d-c1`，退出码 **0**。
- 脚本使用 `nice -n 10`、`MAKEFLAGS=-j4`、`-XX:ActiveProcessorCount=4`；Alan `nproc=20`，本任务一次仅运行一个仿真，构建并发上限 4。
- 日志根：`/home/chen/FUN/flow-r02-evidence/63fbe8d-c1/`；`emit-512-512.log`、`emit-256-256.log`、`emit-512-256.log` 三组生成通过；`smoke.log` 报告 **3 succeeded, 0 failed**；`rtl/<配置>/filelist.f` 列出生成文件。
- 此结果仅证明三组配置的生成、Verilator 编译和复位空闲状态；不代表指令功能、性能或综合通过。
- 参数默认：VLEN=DLEN=512、8 lane、VIQ 32、五队列深度 16/8/16/2/4、4 写 bank、4 共用执行读口、512 位 AXI、单 ID、多 outstanding、4 beat burst、16 KiB 返回缓冲、32 条在途访存、8 个翻译编号、32 B L1D 作废行。
- FP 队列和 sequencer 已存在；serialGo 与未实现指令均断言。返回缓冲使用同步读存储，VRF 按字节写使能和低位行 bank 仲裁。
- C1 生成有动态索引宽度警告；C2 在不改变结构的前提下收敛索引宽度，并把翻译返回的寄存器更新拆成静态目的位置，减少多路选择器规模。后续 RTL 修改需要重新验证 C1。

### C1 重跑与 C2 闭合

- 实现与测试提交：`3a5202e`，已 push。Alan 分支与 HEAD 相符，工作区无已跟踪修改。
- cwd：`/home/chen/FUN/flow-rvv-r02-20261007`。
- 命令（激活 flow 后）：`bash rvv/r02/run-c1.sh /home/chen/FUN/flow-r02-evidence/3a5202e-c1 && bash rvv/r02/run-c2.sh /home/chen/FUN/flow-r02-evidence/3a5202e-c2`，退出码 **0**。
- C1：三个 `emit-*.log` 生成通过，`3a5202e-c1/smoke.log` **3/3**。VRF 已收敛为每 bank 一个物理写口；翻译返回采用静态目的索引，不改变队列/判定/区间结构。当前生成没有原 C1 的动态索引宽度警告。
- C2 日志根：`/home/chen/FUN/flow-r02-evidence/3a5202e-c2/`。
- `frontend.log`：**5/5**，包含 kill 后迟到响应、编号不提前复用、判定/kill 同拍撤销、commit/kill 同拍保留最老项、最后一个 VIQ 位置、已提交且仍排队的 load/store 查询及提交当拍查询、跨页两个非连续 PA、所有 Serial 分类。
- `integration.log`：定向指令/非对齐/跨页/掩码/尾部 undisturbed/LMUL/标量结果/三类重叠与不重叠对照，在握手种子 **0–7** 下全部通过；最终内存与全部 32 个 VRF 寄存器的 store 转储逐字节匹配实际 Spike 结果。每组读取 92 个 AXI beat。
- 驱动器独立检查 AR/AW 的 PA、burst 长度和 4 KiB 边界；按已提交访存跟踪 R/B 生命周期，显式断言三类重叠排序；每拍查询尚未完成的已提交访存，包括提交当拍；WSTRB 与每个被写行的作废覆盖均检查。
- `R02_WAR_BYPASS`：每组均出现多个 load 并存，且都有年轻 AR 在老 load 的 VRF 写回因 WAR 受阻时发出。峰值并存 **8–15 条 load**，每组 **1–4 次** AR 在 WAR 等待期间 fire；不以结果一致替代该机制证据。
- `plugin-test.log`：**15 条**点积指令的独立字节算术检查通过；它从 Spike 的执行前后快照检查 SS/SU 符号解释和模 2^32 累加，不使用 DUT 运算逻辑。
- 本段证明独立协处理器的 R02 子集，不包含 Breeze 集成、FP、串行执行、FPGA 或 Linux 运行证据。

### 工具与参考语义

- Chisel **7.0.0**、Scala **2.13.16**、sbt **1.9.7**，使用原 `design/build.sbt`，未修改共享构建配置。
- Alan Verilator **5.028**，OpenJDK **11.0.32.1**；版本文件见 C1 日志根。
- Spike **1.1.1-dev**，源码 `76ce016b6765d66c93522b0ea9a16a44841cb331`；源码状态、插件哈希见 `reference/`。使用安装的独立 Spike 程序，不改其工作区。
- Spike 二进制 SHA256：`c0a8eb834cc94e92afb372f29bd7d2a87215c5fb6ee0dc19ed84792e64222c2a`。
- ISA 选择 `rv64gcv_zicclsm_zvl512b`：Zvl 指定实际 VLEN；Zicclsm 与本任务允许非对齐的快路径相符。每个参考程序都由 Spike 实际执行，记录 CSR 与标量操作数，最终内存来自其执行结果。
- Zvqdotq 固定提交：[`813cba14c9f0a731b4904925851a2820a6320b5b`](https://github.com/riscv/riscv-dot-product/tree/813cba14c9f0a731b4904925851a2820a6320b5b)。`vqdot.vx` 的固定匹配值 `0xb0006057`，`vqdotsu.vx` 为 `0xa8006057`，两者匹配掩码 `0xfc00707f`；vd/vs2/rs1/vm 按标准 OP-V 字段填入。
- 采用正文的四个 int8 乘积加 vd、32 位回绕语义；官方 Sail 示例最后引用了未定义的 `product`，插件采用正文定义的结果并独立检查。结构无需改变。
- 发起时分配 64 位单调年龄号；判定/commit 不依赖核心流水级数。单 AXI ID 对应多 outstanding，按 burst FIFO 归还；没有多 ID 乱序返回实现或证据。
- ALU/乘加按行推进、按整个寄存器完成释放读写掩码；可在最后一行 fire 当拍接纳下一条同单元指令。VRF 六个读口中四个执行读口按需求/年龄分配，store/跨 lane 各自独立。
- 访存区间存储在判定得到 PA 后有效，提交当拍旁路到查询，释放与 VRF 写回分离。翻译请求编号独立于被 kill 的描述符，迟到响应仍接收并丢弃。
- 当前定向程序使用 tu/mu，对尾部与屏蔽前值逐字节检查；不把不同 agnostic 取值判为错误。VL=0 不翻译/不访存，`vmv.x.s` 按 ISA 仍读取元素 0。

### 修复与诊断记录

以下均保留原日志，没有放宽合同、删除用例、修改预期值或增加测试特判：

| 提交/尝试 | 分类与结果 | 处理 |
| --- | --- | --- |
| 第一次 Alan clone/fetch | 基础设施：TLS 中断、直连超时；C2 未运行 | 建独立 clone，再用本任务临时 SSH 转发从 GitHub fetch；其他任务工作区不变 |
| `3ff6d1c-c2` | 测试基础设施：插件 const API 编译失败 | 按安装的 Spike API 修正对象限定 |
| `31026c3-c2` | 测试基础设施：C++20 与 Spike include 路径冲突 | 使用 C++17，保留头文件警告，不改变语义 |
| `eb98880-c2` | 测试基础设施：安装版本没有 --varch | 改用 ISA Zvl，并检查记录的实际 VLEN |
| `e586032-c2` | 测试基础设施：RVC 使 checkpoint PC 为半字对齐，按 uint32 数据读取下一指令触发未对齐异常 | 按四个字节取原始指令；加 trap 诊断出口，避免死循环 |
| `a9a2c8f-c2` | 测试程序语义：非法 SEW/分数 LMUL 置 vill | mf8 用 e8、mf4 用 e16，保留全部 LMUL；点积 SEW=32 限定 LMUL≥mf2；加 vill 检查 |
| `23c9585-c2` | 测试基础设施：辅助驱动器未继承 PeekPokeAPI | 补 API mixin，不改刺激或比较 |
| RTL 源码修正 | 单 bank 多个条件写调用可能妨碍单写口推断；空操作完成可能与 store 完成争用更新口 | 显式单 bank 仲裁后只调用一次写口；互斥完成上报。属于既定结构的实现修正 |

### C3 首次执行与实现符合性修正

- `36ca203` 已 push；命令 `bash rvv/r02/run-c3.sh /home/chen/FUN/flow-r02-evidence/36ca203-c3`，退出码 **1**，`exit-code.txt` 记录。
- `c2-check/` 再次通过；`fixtures/seed-0000` 至 `seed-0999` 的 Spike 参考已全部生成，`plugin-test.log` 独立检查 **10,675 条**点积通过。
- `random.log`：DUT seed 0–6 通过，seed 7 的已提交访存区间查询检查失败。保留参考和失败日志；补充年龄、地址、完成状态诊断后继续定位，不放宽查询要求。
- 实现符合性：初版 SEW=64 vmacc 使用全行并行乘法，设计 3.8 明确列为慢路径。改为既有乘加 sequencer 内先锁存源行，再复用一个 64 位乘法器逐元素计算，最后一次写 bank；仍按寄存器粒度释放读写掩码，不改变五队列、scoreboard 或 VRF 结构。SEW=32 点积通路保持原吞吐。
- 修改后重跑 C1、C2、C3。1000 组参考可复用，但脚本必须核对参考生成器、插件、链接脚本、Spike 源码/二进制身份完全相同；DUT 的 1000 组全部重新执行并绑定新 SHA。参考复用不复用旧 DUT 通过结论。
- `c2e436f` 已 push；命令 `bash rvv/r02/run-c1.sh /home/chen/FUN/flow-r02-evidence/c2e436f-c1` 退出码 **0**，三组 emit 日志生成通过，`smoke.log` **3/3**。
- 同 SHA 的 C2 命令：`bash rvv/r02/run-c2.sh /home/chen/FUN/flow-r02-evidence/c2e436f-c3/c2-check`（由 C3 入口先执行），退出码 **0**，前端 **5/5**、定向 **8/8**，慢路径乘加结果严格匹配 Spike，WAR 越位检查全部通过。日志 `c2-check/frontend.log`、`c2-check/integration.log`。C3 随机重验正在进行。
- `c2e436f-c3` 再次在 seed 7 失败（退出码 1）。`9053b17` 增加临时生命周期 printf，诊断运行在 `fb-debug/`，退出码 1。确认 RTL 错误：区间 payload 不复位，重复复位后无效项可能残留与本轮有效项相同的 age；释放比较没有 valid 门控，同拍分配新项时被旧 payload 的匹配覆盖。例如 age 162 分配到 slot 21 后，旧残留 age 131 的释放把它清空，随后 age 164 重用 slot 21；缺失的是尚未发 AW 的已提交 store。不是查询模型问题。
- 修正 commit/release 对区间的更新均以当前 valid 为前提；保留 payload 不复位的正常实现，移除临时 printf。新增复位后残留 age + 同拍释放/分配的定向回归，仍检查提交当拍与提交后查询命中。冻结区间表结构不变，重新执行 C1–C3。
- 参数实现检查另发现 DLEN<VLEN 时，同拍写两个 v0 子行会因两次整份 shadow 赋值丢失前一 bank 的更新。改成各 bank 合并字节更新后一次写入 v0 shadow，VRF bank 写口仍不变；新增双子行影子、字节使能及同 bank 年龄仲裁测试。属于参数化实现修正，加入 C2 gate 后统一重验。
- `ab1ae1d` 的中间验证 `fix-valid-c1` 因合并影子修正而主动停止，保留部分日志，不算通过；只停止本任务脚本及其子进程。
- `3644575` 已 push。cwd 同前；命令 `bash rvv/r02/run-c1.sh /home/chen/FUN/flow-r02-evidence/3644575-c1`，退出码 **0**；三个 emit 日志和 `smoke.log` **3/3**。后接 `R02_REUSE_REFERENCE=/home/chen/FUN/flow-r02-evidence/36ca203-c3 bash rvv/r02/run-c3.sh /home/chen/FUN/flow-r02-evidence/3644575-c3`，先执行 C2，仍在运行。
- `3644575-c3` 退出码 **1**：C2 前端 **6/6**，新增区间回归通过；VRF 双子行与仲裁检查通过，但最终 row 4 比较失败。分类为测试初始化遗漏：该行只写过低字节，却把其余从未初始化字节预期为 0。先整行初始化为 0x33，再只改低字节，并逐位比较“0x33 保留 + 0x77 低字节”；增加有效的保留字节检查，不改变 RTL、不屏蔽比较。C3 尚未启动。
- `4c1fc28` 已 push；`git diff --exit-code 3644575 4c1fc28 -- design/src/main/scala/rvv` 退出码 **0**，C1 对应 RTL 完全一致；仅修正 VRF 测试初始化与报告。
- C2 命令：`bash rvv/r02/run-c2.sh /home/chen/FUN/flow-r02-evidence/final-c3/c2-check`（C3 入口先执行），退出码 **0**。`frontend.log` **6/6**，`vrf.log` **1/1**，`integration.log` **8/8**、全部匹配 Spike，WAR 越位/多 load 机制检查通过。C3 继续运行。

与冻结设计结构不一致：**无**。C3、P1–P5、S1 继续按原合同执行。

### C3 闭合

- 验证 SHA：`4c1fc2803678146fcd15c6a82c00f8af8d084516`，已 push；Alan cwd 同前，工作区无已跟踪修改。
- 命令：`R02_REUSE_REFERENCE=/home/chen/FUN/flow-r02-evidence/36ca203-c3 bash rvv/r02/run-c3.sh /home/chen/FUN/flow-r02-evidence/final-c3`；退出码 **0**，`exit-code.txt` 记录。
- `random.log`：种子 **0–999，1000/1000**，每组 64 条随机向量指令，另有 32 次初始化 load 与 32 次最终 VRF store 转储。随机 LMUL、VL、ALU 掩码、寄存器角色、重叠访存地址、提交延迟、翻译响应及 AXI 反压，并随机 kill/重新发起。所有最终内存与 VRF 转储逐字节匹配 Spike；所有挂载/排序/信用/作废断言保持启用。
- 原 seed 7 缺陷通过；新增区间复位回归与 VRF 字节使能回归属于 C2 前置 gate，均通过。
- `spike-random.log` 记录参考复用来源；生成器/插件/链接脚本/构建脚本 Git diff、Spike 源码身份和干净状态、二进制 SHA256 均核对。没有复用任何旧版 DUT 通过结论。
- `plugin-test.log`：独立检查 **10,675 条**点积通过。单个仿真实例，`nice -n 10`，没有形式化执行。
- 后续 P/S 只新增测试与综合脚本；如 RTL 不变，保留 C1–C3 精确 SHA 的既有证据，不把文档或测试入口的新 SHA 自动写成旧验证 SHA。

### P1–P5 首轮测量

- `d904bd1` 已 push，RTL 与 C1–C3 完全相同；命令 `bash rvv/r02/run-performance.sh /home/chen/FUN/flow-r02-evidence/gemv-performance`，退出码 **0**。`performance.log` 3 个测试成功代表结果/协议正确及测量完整，不代表每条性能合同通过；按下表单独判断。
- 64 行 × K=896，e32/m4、VL=64，224 次 256 B 权重 load、224 次 dot、每 8 次 dot 做块累加与清零、最后 store；严格匹配实际 Spike。`plugin-test.log` 独立点积 **224 条**通过。
- 核心 commit 间隔 0、无 kill/随机停顿，每对 load→dot 插入 1 拍标量 lw 占位；翻译固定 2 拍；单 ID AXI 无反压，读延迟分别 1/40 拍，B 延迟 1 拍。参考程序的 CSR/checkpoint 与标量操作数装载属于记录设施，不计入内核模型。

| 条件 | 读字节 | 首个至末个 R 的拍数 | 峰值比例 | 内核总拍数（首 issue→末 store B） | 总拍数/896 理想拍 | B 时返回缓冲剩余字节 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| P1：1 拍 / 16 KiB | 57,344 | 896 | 100.00% | 972 | 1.08482 | 0 |
| P2：40 拍 / 16 KiB | 57,344 | 923 | 97.07% | 1011 | 1.12835 | 0 |
| P3：40 拍 / 2,560 B | 57,344 | 1006 | 89.07% | 1067 | 1.19085 | 0 |

- P4 默认配置：P1/P2 均为 **8 对 1 拍、190 对 3 拍、26 对 4 拍**，最大 4 拍，合同 **≤2 拍**，因此 **未达标**。不只选择首对，也不排除块边界。P3 为 1–3 拍，仅作非默认缓冲对照。
- P5：P1 全部 223 对提前 **3–70 拍**，P2 全部提前 **42–70 拍**，均通过；P3 为 **38–50 拍**。
- 原始值在 `results/P1.json`、`P2.json`、`P3.json`；`*-pairs.json` 保存所有配对延迟、提前量及原合同判断。
- 针对 P4 加入只在非随机性能运行输出的事件 CSV，记录首 AR、首寄存器写完、依赖 MAC 开始读、前一 MAC 完成时刻。不改刺激、RTL 或阈值；补 P1 时间线后记录原因，按 0.3 节继续 S1。
