# 独立前端验证记录

实现与边界见 [规格](../../docs/frontend-pipeline-design.md) 和 [验收报告](../../docs/tasks/frontend-pipeline-20261010-report.md)。当前最终功能 gate 为 `tests-14`，62/62；对应 RTL 生成为 `rtl-08`。源码摘要 `778553faff6ff7c72e3f7304c6278ffea8f01be383a5aa6e7bedd28bab91a6fc`，基线 HEAD `9747dfebf61930c1f4a9934b2a8aab6425ae72ff`。最终物理 gate 为 `route-16`，100 MHz routed WNS +0.143 ns、WHS +0.028 ns，exit=0；核对结果见 [acceptance.json](acceptance.json)。

实际主机、cwd、argv、UTC 和 exit 分别在每个 stage 的 `command.json/result.json` 中。`source-manifest.json` 绑定逐文件输入；Vivado stage 的 `input-manifest.json` 还绑定生成 SV、物理上下文 Tcl 和 XDC。导出包的 `*-manifest.json` 逐文件 SHA256 已在本地核对；DCP 保留 Alan，其绝对路径/大小/哈希在 `checkpoints.json` 中。

工具脚本：

- `snapshot.py`：只捕获输入，不编译；生成 source.tgz 和 source manifest。
- `run.py`：仅在经过共享配置预检的执行主机运行 sbt，保存每次实际命令和新生成的 JUnit XML。生成 timing harness 前清除 task-owned 旧输出，避免混用陈旧模块。
- `implement.py/implement.tcl`：Alan 上新建不可覆盖的 stage，复制完整 RTL 和约束，执行独立 synth/place/route，并以 setup/hold 均非负为进程退出门槛。
- `ooc-context.tcl`：为 OOC 数据端口分配已放置边界逻辑附近的 INT 虚拟引脚，保存 partpins.csv/xdc；不改变时序约束。
- `collect.py`：仅打包已结束 stage 的日志、报告、输入和新 JUnit；记录大 DCP 哈希，不打包 `.Xil`。
- `audit.py`：本地核对导出、输入、当前 design/config、同源测试/RTL/route、JUnit failure/error/skip、全路由状态、check_timing、DRC 和完整时序通过声明；成功后写 acceptance.json。

10 ns、uncertainty 0.1 ns、I/O max 2 ns/min 0.2 ns 的 `frontend.xdc` 未放松，没有 false path 或 multicycle。外部 root-clock 输入在验证环境侧经 1918 个显式负沿采样寄存器，再进入原正沿边界；phase-interface-02 的 8 个时钟相位、至少 80000 次向量检查通过。harness 内置真实 BUFGCE，数据边界为显式寄存器 FIFO；这里证明的范围是该独立 OOC 物理上下文，不是整 SoC、板级时钟、bitstream 或上板。

历史候选须保留其结果边界：route-01/02 是脚本/名称错误；route-03/04/06/07/08 在被后续版本替代时精确终止，并保存原因；route-05 完整 routed setup 正、hold 负，未通过。route-09 同源 setup 正、外部输入 hold 负；route-10 Vivado 崩溃 exit=139；route-11/13/14 是物理脚本错误；route-12 setup 正、内部短路径 hold 负；route-15 有引脚映射冲突及未路由网络。它们都不能充当最终源码的 routed 通过证据。最终 route-16 从有效 route-12 检查点移动 124 个实测短路径接收寄存器后完整重布线，未沿用 route-15 网表。

物理复现链为 `implement.py` 的综合/放置检查点 → `phase-candidate.py` 的独立环境采样与布线 → `move-hold.py` 的实测短路径放置修复。每步使用独立 stage；Tcl/XDC/SV 和来源检查点哈希均已保留。
