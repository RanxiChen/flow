# Zve32f R07c 执行记录（2026-10-13）

工作树 `/home/chen/leisure/flow/.worktrees/zve32f-single`，分支 `feat/zve32f-single-20261013`。R07c RTL 和任务书提交 `eb12b24`；差分测试及 R07c-1 仿真断言提交 `b585c9a93a115ab167ce1b57d0dbeadc5b2d3ac2`。远端执行目录 `/home/chen/FUN/flow-runs/zve32f-r07c-20261013/src` 是从 R07b `4cbc473` 的独立源快照复制后同步 R07c 改动；wrapper、service、差分测试的本地与远端 SHA256 逐个一致。远端证据根为 `/home/chen/FUN/flow-runs/zve32f-r07c-20261013`，本目录保存日志副本。

按 `docs/cross-project/simulation-host.md` 在执行前重新预检：首选 `cloud_chen@47.96.71.231:22` 的 `BatchMode=yes, ConnectTimeout=8` SSH 超时（exit 255）；备用 Alan `chen@localhost:2286` 经 `clawbot` 免密连接成功。实际编译、RTL 生成、仿真主机均为 Alan。其 `flow` 环境激活成功，Java 11.0.32.1、SBT 1.9.7、Verilator 5.028，预检时 `/home/chen/FUN` 可用 68 GiB、内存 available 57 GiB，无在运行的 sbt/Vivado/Verilator 任务。Alan 的 `sbt` shell 入口指向已不存在的 1.11.2 安装，使用现存 `/home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar`，`show sbtVersion` exit 0。未启动 Vivado。

## §11.2 五条依据复核

| 条 | 复核结果 |
|---|---|
| 1 | `ZveCoprocessor` 中 `ZveSharedFpMux` 仅接 slow 与 cross。`ZveSlowUnit` 只允许 vfmul/vfdiv/vfrdiv/vfsqrt；其 `ZveFpVectorEngine` 请求 src/dst 固定 0。cross `ZveElementEngine` 的 FP 请求也固定 0，只产生 ADD、MINMAX、CMP。无转换或 FP64 请求路径。新增 R07c-1 断言覆盖 service 入口；本次差分首例未触发。 |
| 2 | wrapper 的 KIND=3 选择 WIDTH=32、FP32 feature、ADDMUL PARALLEL、DIVSQRT MERGED、NONCOMP PARALLEL、CONV DISABLED；KIND=2 的配置未改。CVFPU 的 THMULTI 源码仅支持 WIDTH 16/32/64，任务书“任意 WIDTH”表述过宽，本次 32 位属于支持范围。**数值等价不成立**：首个 DIV 请求已使 R07c-2 失败，见下节。 |
| 3 | KIND=3 低 32 位输出由 wrapper NaN-box；cross 对 CMP 只取 bit 0，对普通元素按 destination mask 截取低位；reduction 最终也只写目标元素宽度。高 32 位差异在这些消费点不可见。此为静态复核，未完成完整回归。 |
| 4 | `ZveFeatures.EnableNanBox=0`，WIDTH=32 时送入 fpnew 的是各 operand `[31:0]`。slow/cross 客户端构造 boxed FP32，低 32 位保留原值。DIV 反例表明仅凭这些连线不足以保证 THMULTI 等价。 |
| 5 | `FlowFpnewBlackBox`、标量 `FpUnit` 未被 R07c 修改。`single` 与 `single-rvv` 的重新生成 E1 逐文件通过，见下节。 |

## §11.4 顺序执行及停点

| 步骤 | Alan 命令或检查 | 结果 |
|---|---|---|
| 1 编译 | `cd .../src/design; JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 java -jar .../sbt-launch-1.9.7.jar compile` | exit 0；[日志](compile.log)。执行时源码为 `eb12b24`。 |
| 1 RTL | 同一 launcher，顺序 `runMain flow.top.GenerateBreezeCluster {single,single-rvv,single-zve32f} gshare linux coherent-dma` | 三项均 exit 0；[日志](rtl-gen.log)。执行时源码为 `eb12b24`。 |
| 1 E1 | R07b 的 `compare_e1_r07b.py` 比较 R07b 与 R07c 输出，只归一化绝对源码路径和 Scala 断言行号 | exit 0；`single` 85/85、`single-rvv` 118/118，同名、无增删、逐文件相同；[JSON](e1.json)。R07b 已与冻结 `94636c6` E1 通过。 |
| 2 差分 | `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 java -jar .../sbt-launch-1.9.7.jar "testOnly flow.rvv.zve.ZveSlowFpKindSpec"` | **exit 1，R07c-2 失败**；[日志](slow-fp-kind.log)。执行源码内容与 `b585c9a` 一致，三项文件 SHA256 已与远端核对。 |
| 3–5 回归、综合、布线、bitstream | §11.5 要求 R07c-2 失败即停下上报 | **未执行**。不存在 R07c LUT、WNS、拥塞或 bitstream 证据。 |

差分测试同时实例化 `ZveFpnewService(2)` 和 `(3)`，请求各有唯一 16 位 tag；准备了 ADD/MUL/DIV/SQRT、MINMAX rm 0/1、CMP rm 0/1/2、五种算术舍入模式、指定特殊值及固定 seed `0x707c32f` 的 10,000 个随机请求，并对两个返回口分别施加固定 seed `0x5032f07c` 的随机反压。测试在第一个隔离请求即失败，因此后面的覆盖**仅为已编写，未实际运行**；不能报告 DIV/SQRT 延迟或随机覆盖通过。

可复现反例：tag 0，operation DIV (4)，rounding 0，src/dst format 0，`a=0xffffffff3f800000` (+1.0f)，`b=0xffffffff40000000` (+2.0f)，`c=0xffffffff00000000`。KIND=2 返回低 32 位 `0x3f000000` (+0.5f)，KIND=3 返回 `0x7fc00000` (qNaN)；双方 status 均为 0。tag 检查通过，比较发生在两边均收到 tag 0 后。失败位置 [ZveSlowFpKindSpec.scala](../../design/src/test/scala/rvv/zve/ZveSlowFpKindSpec.scala) 的结果比较断言。当前只能确认 THMULTI 的 32 位配置与 64 位配置结果不等；根因仍待确定。

遵守 §11.5：未改回 KIND=2，未修改 wrapper 或 golden，未跑后续回归/Vivado，也未调整 Zve 参数、流水或 frontend cache。下一步需用户或 RTL 作者决定 FP32-only DIVSQRT 的修正方案；修正后重新从差分测试开始验证。
