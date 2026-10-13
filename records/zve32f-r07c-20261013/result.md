# Zve32f R07c 执行记录（2026-10-13）

## R07c-2 修复与复跑（当前结果）

工作树 `/home/chen/leisure/flow/.worktrees/zve32f-single`，分支 `feat/zve32f-single-20261013`。Flow 实现提交 `215f18f9a3e0bf32ccd1d00e1937aa32fbce9dd0` 同时包含 §5/§11.4a 文档、差分测试、CVFPU 子模块指针；CVFPU fork 提交 `d0ed6e548bfd298a03f2f8c62e610a5fd1afa91e` 已在用户明确授权后无强推地推送到 `RanxiChen/cvfpu` 的 `flow-zve32f-r07c` 专用分支，并由 `git ls-remote` 核对该分支头。Flow 分支尚未推送。未跟踪的 llama agent 文件未触碰。远端证据根 `/home/chen/FUN/flow-runs/zve32f-r07c-fix-20261013`，实际执行主机 Alan；本目录保存日志和报告副本。

### 根因与契约

§11.2 第 2 条对 THMULTI 补位的推断有误。`fpnew_divsqrt_th_64_multi.sv` 原来把 WIDTH=32 操作数高 32 位补 0，`ct_vfdsu_prepare.v` 的 FP32 分支把它视为未 NaN-box 的输入并产生 canonical NaN。首个 DIV 反例见下方历史记录。fork 只把送往 C910 的两处补位改为 1；WIDTH=64 时补位宽度为 0。FP32-only、wrapper 接口、运算类别和标量 FPU 均未改变。CVFPU 修复文件的本地与 Alan 源快照 SHA256 均为 `3c149b1ec509de2195ea2938f8f03ebc01642fd18f787f5964759628d76cfd7e`；差分测试文件均为 `f192a8e43172bb5e74522c69ea048d5c5d9b02ea533370a7bd98033b73c1d007`。

§11.2 五条依据经重新核对：① slow/cross 两个客户端只发 FP32 的指定操作，且 R07c-1 断言覆盖服务入口；② FP32 ADDMUL/NONCOMP 保持原 slice，DIVSQRT 的原补 0 假设已按上文修正，差分结果见下；③ 高 32 位结果在 cross 的 CMP/元素写回消费点不可见；④ KIND=3 输入仅取低 32 位，boxed/unboxed 高位均经实际差分验证；⑤ 标量 FPU 未改，`single`/`single-rvv` E1 逐文件一致。静态依据与运行证据分别陈述，不将差分或 E1 外推为整机功能证明。

### §11.4 顺序执行

每次开始仿真、编译或 RTL 生成前重新读取 `docs/cross-project/simulation-host.md`。首选 `cloud_chen@47.96.71.231:22` 用 `BatchMode=yes, ConnectTimeout=8` 预检均超时；Alan `chen@localhost:2286` 经 `clawbot` 可用，Java 11.0.32.1、Verilator 5.028，SBT 用现存 1.9.7 launcher。Vivado 2022.2 也在 Alan。测试失败不被视为主机不可用。

| 步骤 | 固定源/命令范围 | 结果与本地证据 |
|---|---|---|
| 2 差分 | `testOnly flow.rvv.zve.ZveSlowFpKindSpec`，KIND=2/3 同请求 | exit 0；10,602 个请求，其中 600 个 directed boxed/unboxed、10,000 个随机；五种舍入、指定特殊值、随机反压和 tag/status/result 检查通过。独立 DIV 和 SQRT 的 KIND2/KIND3 请求到返回延迟均为 11/11 周期。[日志](slow-fp-kind-fixed.log)。 |
| 3 Zve 回归 | `testOnly flow.rvv.zve.*` | exit 0；16 suites、21 tests，含 ZveWindowSpec 和 ZveMountVerdictSpec。[日志](zve-regression-fixed.log)。 |
| 3 系统冒烟 | `testOnly flow.cluster.ZveSystemSmokeSpec` | SBT 日志显示全部通过，含 13 个 riscv-tests，verdict replay 0，`[success] Total time: 37 s`。外层 SSH 在 SBT 结束后未正常回收，手动断开得到传输 exit 255；不把此 transport 状态写成 SBT exit 0。[日志](system-smoke-fixed.log)。 |
| 3 gemv | `testOnly flow.cluster.ZveGemvQ4Spec` | exit 0；4 个 ELF 均通过、maxErrorBits 0、verdict replay 0；周期 scalar 957356、vector 116520、vector_n64 27811、vector_4acc 114176，与 R07b 相同。[日志](gemv-fixed.log)。 |
| 1 复核 RTL/E1 | 三个 profile 重新生成；R07b E1 比较器只归一化绝对源路径和 Scala 断言行号 | 三项 exit 0；`single` 85/85、`single-rvv` 118/118 逐文件相同。[日志](rtl-gen-fixed.log)、[E1](e1-fixed.json)。此项为修复后的 E1 复核，不改变从第 2 步继续的执行顺序。 |
| 4 综合 | 同一生成目录 `kcu105-r07c-01/gateware` 的生产 source prefix 生成 synth-only Tcl；Vivado 2022.2 | exit 0；LUT 175305（72.32%）、FF 73726、DSP 65、BRAM36 167、BRAM18 3；`ZveMount` 101381 LUT、`slowFp` 4634 LUT；综合 WNS −0.259 ns/TNS −21.070 ns/129 端点。最差 10 个不同端点均是标量 L1D `internal3_req_src_reg[1]` 到 frontend cache WEBWE。[利用率](utilization-synth-fixed.rpt)、[层次](utilization-hierarchical-synth-fixed.rpt)、[时序](timing-synth-fixed.rpt)、[路径](worst10-distinct-synth-fixed.rpt)、[绑定](synth-script-binding-fixed.json)。 |
| 5 默认布线 | 从 `r07c-synth.dcp` 出发，place Default、route Default、post-route phys_opt Default | Vivado 报告已完成：WNS −0.471 ns、TNS −355.949 ns、2020 个 setup 违例端点；WHS +0.030 ns、WPWS +0.108 ns，0 个 hold/pulse width 违例；0 个 routing errors。最差路径 frontend BTB→PHT。拥塞 peak 为 North Global level 5、107%，窗口以 backend/csrFile 71%、zveMount/translator 14%、L1D 10% 为主。详见 [时序](route-default/routed-timing.rpt)、[30 条不同端点路径](route-default/worst-30-distinct.rpt)、[拥塞](route-default/congestion.rpt)、[路由状态](route-default/route-status.rpt)。SD IO budget 检查 PASS，但板级假设仍待确认。 |
| 5 Explore 布线 | 从同一综合 DCP 出发，place Explore、pre-route phys_opt AggressiveExplore、route Explore、post-route phys_opt Default | Vivado 报告完成：WNS **−0.001 ns**、TNS −0.001 ns、**1 个 setup 违例端点**；WHS +0.030 ns、WPWS +0.108 ns，hold/pulse width 违例均为 0；0 个 routing errors。拥塞 peak 为 North Global level 5、110%，窗口以 backend/csrFile 69%、zveMount/translator 15%、L1D 10% 为主。最差路径是 `zveMount/unit/window/issued_12_reg` → `zveMount/unit/memory/va5__1/.../CEA1`，属于向量内部的发射控制到 DSP 使能，slack −0.001 ns、13 级逻辑、数据路径延迟 8.861 ns（布线 7.219 ns）。[时序](route-explore/routed-timing.rpt)、[30 条不同端点路径](route-explore/worst-30-distinct.rpt)、[拥塞](route-explore/congestion.rpt)、[路由状态](route-explore/route-status.rpt)。SD IO budget 检查 PASS，但板级假设仍待确认。 |

生成快照共有 140 个 `.sv` 与综合 source snapshot 逐文件一致，[清单](synth-snapshot-fixed.json)；使用的流程脚本为 [综合脚本生成器](make_synth.py)、[布线脚本](route_impl.tcl)及[bitstream 门控脚本](make_bitstream.tcl)。综合、布线和 bitstream 属于不同证据层级。

两种布线策略分别从 SHA256 `e4195bccd48775b7831068ef51e97738f9166cf802e8e47cffd73729f7afd998` 的同一 `r07c-synth.dcp` 起跑，所用 `route_impl.tcl` SHA256 均为 `8db7414e288cf8fd4709b9164bea4d5e1176a719d4c9aea89f9f7583dc2a8d7a`；[默认绑定](route-default/input-checkpoint-sha256.txt)、[Explore 绑定](route-explore/input-checkpoint-sha256.txt)。Alan 上调用为 `vivado -mode batch -source $root/route_impl.tcl -tclargs $gateware default` 和相同调用的 `explore` 版本，均完成并正常退出 Vivado。生成 target 的命令为 `python3 fpga/kcu105/target.py --cpu-type breeze-zve32f --with-sdcard --sys-clk-freq 100000000 --vivado-threads 4 --output-dir $root/kcu105-r07c-01`，exit 0；[target 日志](target-fixed.log)。

**当时的 §11.5 停点：两种布线策略的 WNS 都小于 0，尚不能生成 bitstream。** 此结论仅描述上表的第一次布线；后续原 RTL 的 post-route 物理优化已通过门槛并生成 bitstream，见下一节。Explore 的 1 ps 缺口仍是违例，不能按显示精度或不确定度四舍五入为通过。Explore 最差 30 条不同端点中，11 条为向量内部（window、cross），19 条为标量既有路径（frontend/ITLB/cache 或 backend→frontend cache WEBWE），无向量到标量路径；只有第 1 条 slack 为负，后续 29 条为 0.000–0.050 ns。默认策略的 30 条中，19 条向量内部、2 条标量到向量、9 条标量既有；其最差路径是 frontend BTB→PHT，slack −0.471 ns。完整层次路径分别见两份 30 条报告。拥塞数值见两份 `congestion.rpt`。按 §11.5 停下，不改频率、frontend cache WEBWE、ZveParams 或流水；由用户决定降频或微架构方案。

## 后续 100 MHz 物理优化与可烧录 bitstream

用户确认保持 100 MHz、先优化实现。此轮 **没有修改 RTL**，从上述 Explore 的已布线 DCP（SHA256 `47903fd2ad146d8f72b92575bae13ae4501fdc69f5ce640a78de591b93784f20`）在 Alan 的独立目录 `/home/chen/FUN/flow-runs/zve32f-r07c-postopt-20261013` 运行 [后布线脚本](route_postopt.tcl)：`phys_opt_design -directive AggressiveExplore`，随后 `route_design -directive NoTimingRelaxation`。脚本 SHA256 为 `29165737800cdd43c7b4913139ce0e573be49a796805e246c155b8ce153aff6e`；输出 DCP SHA256 为 `d0bde33e12e75841a88f01464975cacee6164339667b9ce4dcb3edcc81c3a122`。所用器件、Vivado 2022.2、源快照、100 MHz 约束均与上节一致。

最终 [时序](route-postopt/after-timing.rpt)为 WNS **0.000 ns**、TNS 0、0 个 setup 违例端点，WHS +0.030 ns、WPWS +0.108 ns，hold/pulse-width 违例均为 0；[路由状态](route-postopt/route-status.rpt)为 0 个错误。另存 [最差 30 条路径](route-postopt/worst-30-distinct.rpt)、[拥塞](route-postopt/congestion.rpt)、[时序检查](route-postopt/check-timing.rpt)及[物理优化日志](route-postopt/postopt-console.log)。bitstream 前置门槛重新检查同样通过，[门控时序](route-postopt/bitstream-timing-gate.rpt)；Vivado `write_bitstream` 成功，DRC 为 0 errors、204 warnings、12 advisories，[生成日志](route-postopt/bitstream-console.log)。

**Alan 可烧录文件**：`/home/chen/FUN/flow-runs/zve32f-r07c-postopt-20261013/bitstream/xilinx_kcu105.bit`，SHA256 `9b38ab07e6bb0ef0e3661a69db919b2d1a4619fedb2b4ec2f0440b686ef0f1aa`。这版对应原 RTL `215f18f9a3e0bf32ccd1d00e1937aa32fbce9dd0`，没有 ILA；时序在报告精度下余量为零，上板功能和加入 ILA 后的时序尚未验证。后续 Window RTL 实验单独保存在 `experiment/zve32f-window-resource-20261013` 分支，不包含在此 bitstream 或展示分支中。

## R07c-2 初次失败记录（保留历史）

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
