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

当前状态：RTL、测试和规格已修改，尚未执行本候选功能或物理验证。新候选结果不会并入旧完整回归，也不会替换旧两项 Vivado 结果。
