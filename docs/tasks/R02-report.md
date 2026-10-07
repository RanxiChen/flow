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
| C1 | 通过：三组 RTL 生成、Verilator 编译与空闲复位冒烟 3/3 | `63fbe8d`，见下方 C1 证据 |
| C2 | 未运行 | 待 C1 |
| C3 | 未运行 | 待 C2；至少 1000 种子 |
| P1–P5 | 未运行 | 待 C3；保持任务书原测量口径与阈值 |
| S1 | 未运行 | 待性能段；XCKU040 OOC 100 MHz |

## 实现决定与文档处理

- 区间在 Ok 时登记；提交当拍旁路查询；仅匹配已提交项，load 在全部 R 接收后释放、store 在全部 B 后释放。
- kill 撤销被作废项判定；同拍 commit 保留最老项；已握手翻译编号保留到响应被接收并丢弃，禁止提前复用。
- FP、串行执行仅框架；serialGo 触发未实现断言。不做形式化验证，不继续 R01。
- 与冻结设计结构不一致：无。

## 命令与证据

第 0 步提交号由本提交的 git 元数据确定，后续阶段在此记录其准确 SHA 和 push 结果。当前尚未产生 RTL、仿真、综合或系统集成通过证据。

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
