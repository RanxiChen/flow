# 单核流水吞吐定向仿真：后端能流水，部署前端供指串行

2026-10-12，针对板上CoreMark约0.100条/拍的采样，新增最小定向测试并在Alan执行。**后端连续供指达到II=1；相同部署配置的整核，预热后32位指令仍每11拍才发射/退休一条。** 四个整核程序均通过功能签名和顺序检查，但新的吞吐诊断门槛失败，exit1保留，不把它改成通过。

## 需求与测试对应

| 检查 | 独立期望／条件 | 测试与结果 |
|---|---|---|
| 后端独立ALU重叠 | 连续valid、x0源、64条；每拍接收/提交，结果1..64按目的寄存器正确 | BackendPipelineThroughputSpec，PASS |
| 后端RAW依赖 | 连续64条`addi x1,x1,1`，逐条结果1..64；验证旁路不只检查最后一个值 | 同上，PASS、II=1 |
| 后端理想命中load | 64条独立load，原native-L1D合同模型，结果1000..1063；这是接口模型，不是真实cache性能 | 同上，PASS、II=1 |
| 整核无分支窗口 | 四条独立ALU流／RAW链／真实L1D load／显式16位C.ADDI，各64条，原缓存几何、RVC/MMU前端 | ClusterPipelineThroughputSpec，四项功能PASS |
| 预热与序列完整性 | 每个程序同一窗口执行3次；先预热1次，测2次；192个PC必须逐条按序、无丢失/重复 | Scala检查与离线审计均通过 |
| 流水吞吐诊断 | 预热无分支独立ALU窗口至少0.5IPC；新增诊断要求，不是修改冻结P1预算 | 32位与16位独立ALU均FAIL，suite失败、exit1 |
| 排除外存与资源等待 | 测量期间miss、replay、L2/AXI外存读及EX/WB保持的实际观测 | 四项均0 |

没有改变生产RTL、原有golden、seed、规模、watchdog，也没有为了通过测试关掉部署配置的RVC/MMU。整核用 `BreezeClusterPresets.single`、Linux privilege、coherentDma=true、Tandem与现有backendObservation。缓存继承四核配置：line32B、64sets、L1D/L1I各2way、L2 4way/32KiB、2slots、1hart。测量固件在M/Bare执行；不是Linux/Sv39用户态仿真，也不是四核并发性能证据。

## 测量结果

后端三项各64条：ID间隔全为1，提交间隔全为1，首条到末条提交跨度63拍（含两端64拍）。在连续供给下，新的指令可在旧指令提交前进入，存在多指令重叠。日志中的max_inflight=5按“先加本拍ID握手、再减本拍提交”计数；不能把它当作新增5级寄存器或输出队列容量。

| 整核窗口 | 两个预热后窗口跨度 | 退休间隔 | IPC | 两条不同窗口指令同拍EX/commit |
|---|---:|---|---:|---:|
| 32位独立ALU | 694+694拍 /128条 | 11拍，共126个相邻间隔 | 0.092219 | 0 |
| 32位RAW链 | 694+694拍 /128条 | 11拍 | 0.092219 | 0 |
| 真实D-cache命中load | 694+694拍 /128条 | 11拍 | 0.092219 | 0 |
| 16位C.ADDI独立流 | 470+470拍 /128条 | 4拍64次、11拍62次 | 0.136170 | 0 |

IPC分母使用每个窗口首条到末条退休的含端点周期，再求和；不含循环检查和回跳。计数表中的miss、EX/WB保持等采用两个warm窗口之间更大的连续范围，包含循环检查，因此排除项更保守。两段范围内均无EX/WB保持、miss、replay、外部memory AR与L2 memRead。ID发射也基本以11拍间隔出现，稀疏输入已经在进入后端时存在。

离线审计又逐条核对ALU写回结果与所有128条warm load的值，并审核两个JUnit XML：tests2、failures1、errors0、skipped0，backend成功、cluster失败。进程返回非零是预期保留的性能失败，而非主机故障。

第一遍cold load在miss时早提交，`rd_write=0`，结果由late-write返回；当前CSV没有记录late-write payload。初版离线分析误把这个合法占位当作数据错误，纠正了分析器对这一条的合同解释，保留 `audit-scope-note.txt`，不变更仿真golden。该cold写回数据不由这份CSV单独证明；cold功能由固件现有签名检查覆盖，完整warm序列数据另逐条审计。

## 源码定位及解释边界

部署连接 `design/src/main/scala/top/BreezeCluster.scala:54` 实例化的是 **BreezeFrontend**，没有使用独立新前端BreezeFrontendPipeline。

- `BreezeFrontend.scala:286`：RVC路径只有`!s2_validReg`才允许接收下一条指令请求。
- `BreezeInstrRealigner.scala:60`：req.ready仅在Idle；完整流程沿Idle→RequestFirst→WaitFirst→InspectFirst→Emit串行，Emit响应完成才回Idle。相邻两个C指令可复用同一32位word，故4拍与11拍交替。
- `BreezeFetchTranslator` 是阻塞式翻译/取指适配器；`FetchTlbClient` 仅允许一个未完成翻译，经Idle/Send/Wait/Response串行返回。即便没有PTW miss、ICache已热，也不能把这条链的总延迟转换为每拍一条的新请求。
- 后端直接测试保持相同BackendConfig并达到II=1；整核无EX/WB保持、输入却稀疏，与上述源端单所有者流程吻合。

结论是**当前部署前端供指吞吐限制整核，后端流水能力被饥饿输入掩盖**。现有证据不能分摊11拍中每个前端子模块的贡献，也不能把CoreMark总耗时全部归因于单个FSM；仍须测Linux/Sv39/中断与实际CoreMark完整运行。此次任务只加测试和分析，没有把前端替换、修改RTL、重建bit或再次烧板。

此前CoreMark ILA中的hang=1/reasons=1也已查源码：BreezeHangMonitor.scala:50–53的reasons是OR粘滞记录，bit0标记过去达到阈值的无退休区间；当前4096拍持续退休410条、最大间隔13拍，不代表当前仍卡死。它只记录历史阈值，无法从这段波形确定那次阈值对应WFI、等待还是实际异常。

## 执行、版本与原始证据

仿真源 **e11379d92057ebb0338da1dd32ade75285af13c8**；`git diff f88096e6... -- design/src/main config third_party/cvfpu`为空，与CSR修复版bit的生产CPU/配置一致。测试新增仅在src/test及tests/cluster；现有生产接口和冻结合同未修改。

每轮编译/生成前重新读取共享 `docs/cross-project/simulation-host.md`。cloud_chen@47.96.71.231 SSH5秒超时255，Alan可用：Ubuntu Java11.0.32.1、sbt1.9.7、Verilator5.028、RISC-V ELF GCC（精确版本在compiler.txt）。按当前配置恢复Alan loopback18897到WSL7897 HTTP反向代理，验证GitHub HTTP200，使用GitHub push/fetch同步，没有Git bundle。复用旧Scala编译缓存到独立worktree，Zinc按新源码重编测试；RTL/Verilator由本轮仿真新生成，未复用旧仿真可执行文件冒充新测试。

cwd `/home/chen/FUN/flow-be2-pipeline-probe-20261012/design`；CPU6–9/nice10/JAVA ActiveProcessorCount4/MAKEFLAGS-j4/Xmx6G。准确命令：

```sh
bash /home/chen/FUN/flow-runs/be2-env.sh bash \
  /home/chen/FUN/flow-be2-pipeline-probe-20261012/records/be2-pipeline-probe-20261012/run.sh \
  e11379d92057ebb0338da1dd32ade75285af13c8 \
  /home/chen/FUN/flow-be2-pipeline-probe-20261012 \
  /home/chen/FUN/flow-runs/be2-pipeline-probe-e11379d-20261012
```

两suite完成48.319秒，sbt任务52秒；exit1。原始console、CSV、ELF/dump、source/host/cwd/工具、退出码在Alan该evidence目录及同名 `.console`。生成SV、Verilator编译日志在独立worktree/design的测试输出；无关仿真与板上用户进程没有停止。

首轮655ffc7源码Scala编译成功，但CVFPU嵌套common_cells未初始化，backend suite aborted，cluster无CSV，exit1。随后仅初始化锁定的依赖：CVFPU23153ba、common_cells6aeee85、fpu_div_sqrt_mvp86e1f55；补充压缩流测试至e11379d。旧失败console/exit保存 `records/be2-pipeline-probe-20261012/655ffc7-infra/`，不计为吞吐测量。

完整本地证据 `records/be2-pipeline-probe-20261012/e11379d/`，重跑离线审计：

```sh
python3 records/be2-pipeline-probe-20261012/analyze.py \
  records/be2-pipeline-probe-20261012/e11379d
```

审计退出0代表结果/哈希/范围一致，输出verdict仍是BACKEND_PIPELINING_PASS__WHOLE_CORE_THROUGHPUT_FAIL；不将审计成功或四项功能PASS表述为整核流水性能通过。
