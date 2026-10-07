# R03：面积与时序收敛报告

起点 `79f6c5a`，分支 `feat/rvv-20261005`。第 0 步文档提交 `1e53ef4` 已 push。

## 实现与验证规则

本地仅编辑；Alan 独立 clone `/home/chen/FUN/flow-rvv-r03-20261007`；证据根 `/home/chen/FUN/flow-r03-evidence`。执行使用 nice 10、构建/Java/Vivado 并行上限 4（Alan 20 核）；保留其他任务的 clone 和进程。不做形式化、不新增指令、不放宽任何功能或性能合同。原用例全部保留。

## A1：同步读 VRF

每个写 bank × 读副本用 1W1R 同步 READ_FIRST BRAM 模板，无阵列复位，原字节写使能保留；逻辑写向全部副本广播。读使能仅在冒险检查通过时发出；断言禁止有效读与实际写同址。bank 选择寄存后选数据。

整数 sequencer 增加一拍操作数阶段，正常每拍仍读/写一行；BRAM 输出首次停顿时保存到操作数保持寄存器，避免共享读口被其他单元占用后污染数据。慢速 SEW64 乘加复用原迭代机制。store 和跨 lane 增加请求/返回阶段。读掩码仍保守保持到对应写回。

原 VRF 测试的时序断言：异步改地址后立即检查数据 → 有效同步读时钟沿后检查；所有数据预期、写仲裁和 v0 影子检查不变。没有调整其他用例的数值或验收阈值。

综合脚本采用默认 `synth_design` 策略（删除 R02 的 RuntimeOptimized），XCKU040、10 ns、OOC。逐项面积变化均使用本任务默认策略；R02 原数据只作历史对照。

## 各项改动的面积账

| 阶段 | 提交 | 部件前后 LUT/FF/BRAM | 总 LUT / WNS | 验证与日志 |
| --- | --- | --- | --- | --- |
| R02 历史 | 79f6c5a | VRF 44.5k LUT；前端39.7k；记分板26.2k；VLSU52.9k；ALU18.6k；乘加23.9k | 206k / -2.117 ns（RuntimeOptimized） | R02-report |
| A1 | 0cf6c3e | 综合运行中 | 综合运行中 | C1 3/3；C2 前端6/6、VRF1/1、Spike种子0–7通过；`a1/` |

## 最终验收

C1/C2/C3、P1–P5、S1、S2：未运行。面积、时序、功能和带宽尚无 R03 通过声明。后续按 A2/A5 → A4 → A3/A6 → 其他面积手段推进，每段更新实际提交、命令、退出码和日志。

## A2/A5：独立记分板上限与小单元队列

`scoreboardDepth` 独立参数默认 16，替代由队列和访存在途数推导的 83；分派仍同时要求目标队列与记分板空位。VLSU burst 信用不分配记分板条目。队列默认改为 4/2/2/2/2，满时按原 valid/ready 反压。新增 `RvvR03Spec` 检查满表期间不接受分派分配，以及同址写时禁用读、随后有效读取得新值。

命令：激活 flow 后 `bash rvv/r03/run-stage.sh /home/chen/FUN/flow-r03-evidence/a2`，C1/C2/S1 依次运行，各阶段单独记录退出码。当前未运行。R03 C2 包装脚本完整运行 R02 原套件，再运行新增套件，不筛除原用例。

A1 命令：`bash rvv/r02/run-c1.sh .../a1/c1 && bash rvv/r02/run-c2.sh .../a1/c2 && bash rvv/r03/run-s1.sh .../a1/s1`。C1、C2 均完成，原日志目录同前；综合未完成。默认策略基线使用 `/home/chen/FUN/flow-r02-evidence/vrf-inline-s1/rtl`（生成提交0255e8a，其 RVV RTL 与79f6c5a的差异为空），日志 `baseline-default/`；直接 nice 运行 R03 synthesis Tcl，不重新生成或修改基线。
