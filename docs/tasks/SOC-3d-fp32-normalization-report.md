# SOC-3d FP32 归一化边界独立候选

2026-10-09 用户授权：现有单核/四核 Vivado 继续运行，另增加一个优化 FP32 的候选。基线主仓 `30f4b0e02eba45887678bf19a26ee249497bdb01`、CVFPU `3cbca77e2bab5546ac75edfbe6f056c0a14d7fc9`。旧完整回归与物理构建的独立 checkout 不修改。

## 路径依据与实现

基线两种配置 synthesis `sys_clk` 最差为 FP32 FMA `mid_pipe_sum_q_reg[1][47]` → LZC/归一化 → 舍入/OF/UF/NX → `out_pipe_status_q_reg[1][NX]`，slack −0.495 ns，33 级逻辑。只有 synthesis 完整路径已输出，不能将这条路径指认为 live post-place WNS 的具体路径；见 `records/soc3d-30f4b0e-20261009/synth-paths/`。

新候选 FP32 ADDMUL 由 4 级增至 5 级：输入、全精度 pre-adder、sum、未舍入 normalization、rounded output。CVFPU 启用 FP32 的既有 `norm_payload_t` 边界，wrapper 的 FP32 PipeRegs=5。FP64 及其余格式/操作组参数保持。不增加后端级数、修改 commit 点或访存协议；FP32 无停顿返回增加一拍，II=1。归一化 payload 保存 sign/exponent/mantissa/sticky/tininess/eff_sub/rm/special result/status/tag/mask/aux；同一 valid/ready 边界推进、保持、flush，舍入仍只发生一次。

正常连续请求每拍接收，在接收后第五拍开始每拍返回。输出反压向归一化边界传播，payload 随有效项保持。FpUnit 的 kill 不 flush 已提交计算：已提交结果仍返回一次，年轻项失效后排空，killDrain 禁止 tag 复用直到 CVFPU busy 清零。

## 验证范围

新增三项自检，期望来自冻结提交/依赖合同和独立 IEEE 位模式：

| 要求 | 测试 |
| --- | --- |
| FP32 五拍、II=1、f0、tag/结果/flags 对齐 | 16 个连续 ADD/MUL/FMA 请求，交替精确融合抵消、NV、UF/NX、RNE tie，检查每项恰在 n+5 拍返回 |
| 新边界占用及反压下的 kill/提交/排空 | 满流水第三项后 kill，只保留已提交 f1；输出停顿、恢复只返回一次、排空后 40 项跨 tag 回绕 |
| 后端实际 FP32 依赖和 CSR drain | FADD.S 依赖同拍 RAW 解除、EX+5 写回、第二项最终写回后下一拍 CSR 读 fflags=1，数值/NaN boxing 检查 |

同时运行完整 `FpUnitSpec`（原数值/五种舍入模式、subnormal 边界、S/D 随机反压、跨组返回、commit/kill 与 tag 回绕判据不改）、后端 T14–T17/P07/P08 子集和新的 FP32 集成用例。功能门槛通过后在 cloud_chen 生成单核生产 `single gshare linux` RTL，传至 Alan 独立 checkout，通过相同 LiteX 入口执行单核 Vivado；100 MHz、器件、directives 与 maxThreads=4 保持，无 timeout，自动记录 UTC 起止及 wall time。

实现记录（14:06）：新主仓 `56c121eccc23e0f1f6854c32d1934e7b786f8337`、CVFPU `b32aeeb6eda9a61b99185e39e78b74d4fb5340be` 已提交并经 GitHub 同步；cloud_chen/Alan 两个独立 checkout 均通过 509 个 RTL/测试输入匹配检查。云机功能门槛运行中，新增的两项 FP32 单元测试已通过；尚未完成全部门槛或新物理验证。新候选结果不会并入旧完整回归，也不会替换旧两项 Vivado 结果。

## 同版本定向验证结果与第三项构建

主仓 `56c121eccc23e0f1f6854c32d1934e7b786f8337`、CVFPU `b32aeeb6eda9a61b99185e39e78b74d4fb5340be`。相对基线的 509 项测试输入只改变 wrapper、CVFPU FMA、两个测试文件，见 [input-diff.json](../../records/soc3d-fp32-20261009/input-diff.json)；没有其他 RTL 或配置改动。

cloud_chen 定向门槛 **17/17 PASS**，无失败/错误/跳过：FpUnitSpec 完整 10/10，后端所选 T14/T15/T16/T17/P07/P08 + 新 FP32 集成用例 7/7；三项新增用例均通过，既有 numerical/flags/commit/kill golden 未改。原始 JUnit 的套件和测试名逐项核对，见 [acceptance.json](../../records/soc3d-fp32-20261009/acceptance.json)。

| 阶段 | 北京时间开始/结束 | monotonic wall 秒 | exit |
| --- | --- | ---: | ---: |
| FP 完整单元门槛 | 14:04:48.094 / 14:07:40.975 | 172.881 | 0 |
| 后端 FP 子集 | 14:07:40.976 / 14:10:15.825 | 154.848 | 0 |
| single gshare linux 生产 RTL 生成 | 14:10:15.826 / 14:10:22.609 | 6.783 | 0 |

功能命令累计 wall 327.729 秒，RTL 生成另列。结束后 509/509 输入再次匹配，clean checkout 与 source SHA 校验保持；详细 cwd/argv/UTC 见各 result JSON。功能原始日志、JUnit、两个执行套件的 ChiselSim HDL/驱动/构建记录在 `functional-evidence.tgz`，SHA256 `1d6b1a785d0c7f8ee73ae97b87aa37185eed683a33105bc067024bab002506db`；生产 RTL 包 SHA256 `477c41a143b2487d2393495a5cf13dcb4f63c48dfbe180c5910567e4c9dbb41b`，取回后匹配远端摘要，见 [archive-verification.json](../../records/soc3d-fp32-20261009/archive-verification.json)。

新增单核 LiteX → Vivado 在 Alan **14:11:36.138621** 启动，当前已进入综合且无 ERROR；入口 `breeze-tiny`、100 MHz、`xcku040-ffva1156-2-e`、Vivado 2022.2、maxThreads=4，原 directives/constraints 保持，无 timeout。独立仓 `/home/chen/FUN/flow-soc3d-fp32-56c121e-tiny`，evidence `/home/chen/FUN/flow-runs/soc3d-fp32-56c121e`。真 UTC 起止/monotonic wall/exit 自动记录，不以轮询估算完整时长；见 [launched.json](../../records/soc3d-fp32-20261009/launched.json)。

现有 `30f4b0e` 两项 Vivado 的 PID/start_ticks 均保持且仍在运行，仅做只读存活确认，[保留证据](../../records/soc3d-fp32-20261009/original-builds-preserved.json)。原完整回归也继续使用旧 checkout，不混入新候选结果。

范围边界：17 个定向用例通过，不等于新候选完整回归、形式等价或数值穷举；新增物理作业尚无最终资源/routed setup/hold、bitstream 或上板结论。结果文档更新发生于验证之后，功能和物理输入固定为上述 source SHA。
