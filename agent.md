# Frontend Agent Notes

本文件记录当前仓库里 `BreezeFrontend` 的开发目标、协作方式和当前实现状态。

## 当前目标

当前正在完善的模块是：

- `design/src/main/scala/frontend/BreezeFrontend.scala`

目标不是一次性写完完整前端，而是按流水线逐阶段补齐：

1. 先把阶段定义、寄存器和接口关系写清楚
2. 再把 `BreezeCache` 接进前端
3. 再把指令和预测信息送到 fetch buffer
4. 最后细化阻塞、预测、异常和 flush 行为

## 当前设计理念

当前前端采用以下原则：

1. 地址统一按虚拟地址处理。
2. `BreezeCache` 在前端模块内部实例化。
3. 流水线按 `S0/S1/S2/S3` 分块组织。
4. 代码组织优先强调“按阶段看得清楚”，而不是先追求功能铺满。
5. 当前优先做骨架和信息流，后续再细化握手与阻塞。

## 当前已实现

目前已经实现：

1. `BreezeFrontendConfig`
2. 前端重定向接口 `FrontendRedirectIO`
3. fetch buffer 输出接口和预测信息类型
4. `FrontendPredType`，当前只有 `NONE` 和 `JAL`
5. 前端的 `S0/S1/S2/S3` 基础寄存结构
6. `S0` 的 next-pc 选择逻辑
7. `S1` 把 PC 喂给 `BreezeCache`
8. `S2` 跟踪发给 cache 的请求
9. `S3` 锁存 cache 返回的 PC 和指令
10. `MiniDecode` 对 `JAL` 的快速识别和预测目标生成

## 相关文档

前端当前设计说明记录在：

- `docs/breezefrontend.md`

后续如果继续完善前端，优先先更新这份文档，再继续扩实现。

## 当前协作规则

当前这部分开发采用如下方式：

1. 修改代码前，先向用户说明准备怎么改、为什么改。
2. 用户确认后再动代码。
3. 每次实现一小步后，由用户先看代码。
4. 用户认可后再决定是否运行验证命令。
5. 用户认可这一小步结果后，再单独提交 git。
6. 等一个大功能完成后，再统一做 rebase/squash 整理历史。

## 本轮前端联调流程

本轮围绕 `breezefrontendSpec.scala` 的仿真代码补齐，按以下流程推进：

1. 先在 `design/src/main/scala/frontend/BreezeFrontend.scala` 中增加一个 optional 调试 IO 口。
2. 这个 optional IO 的组织方式参考 `design/src/main/scala/cache/BreezeCache.scala` 里的 `dump` 信号。
3. 先把口子和最终驱动位置搭出来，再在你的指导下，一点点把需要观察的内部信号往外拉。
4. 每次只补一小段信号或一小段测试代码，不一次性铺开。
5. 测试侧主要在 `design/src/test/scala/frontend/breezefrontendSpec.scala` 中逐步补充。
6. 每补一小步，就先看结果，再决定下一步补哪里。
7. 如果遇到 `assert` / `expect` 失败，第一步不是直接修改源代码。
8. 遇到失败时，先分析失败原因，整理现象、可能原因和我的猜测，再通知用户。
9. 只有在用户给出进一步指令后，才继续决定是否修改源代码或调整测试。

本轮工作的目标不是一次性写完整套前端仿真，而是按“先开观测口、再逐步拉信号、再逐步补测试、失败先分析”的方式稳定推进。

## 本轮当前进展

当前已经完成的第一小步如下：

1. `design/src/main/scala/frontend/BreezeFrontend.scala` 已增加 optional debug IO。
2. 当前 debug IO 里只先拉出了 `s1_pcReg`，不额外扩更多观测信号。
3. `design/src/test/scala/frontend/breezefrontendSpec.scala` 已增加最小复位测试。
4. 当前测试做的事情是：给 `resetAddr` 赋值 `0x0`，手动拉高复位，然后检查 `s1_pcReg` 是否回到 `0x0`。
5. 这一小步已经完成一次实测通过，后续继续按同样方式一点点扩展。

## 当前验证约定

当前前端模块的生成入口是：

```bash
cd /home/chen/FUN/flow/design
sbt "runMain flow.frontend.FireBreezeFrontend"
```

以后如果要验证 `BreezeFrontend` 当前是否还能正常展开，优先运行这个入口，而不是只做普通 compile。

当前这轮已经执行通过的前端测试命令是：

```bash
cd /home/chen/FUN/flow/design
sbt 'testOnly flow.frontend.BreezeFrontendSpec'
```

## 接下来一段时间的协作流程

接下来围绕前端逐周期行为的测试与实现，按下面这套固定流程推进：

1. 先由用户描述想要的前端行为，用文字版把每个周期的流水状态说清楚。
2. 我根据这份文字描述，整理成更规整的文字版时序说明，供双方确认。
3. 在文字版确认后，我再把这份时序翻成 `WaveDrom` 波形图，放在 `docs/` 下维护。
4. 只有当用户认为 `WaveDrom` 波形已经准确表达目标行为后，才进入代码阶段。
5. 进入代码阶段后，先在源代码里增加需要的 debug/观测引脚，把波形里要看的内部状态拉出来。
6. 然后再在测试代码里按波形图逐周期写 `expect` / `assert`，对齐目标行为。
7. 如果测试暴露出当前实现和目标行为不一致，再回头修改源代码。
8. 修改源代码后，继续以“文字版 -> 波形图 -> 观测信号 -> 测试断言”的同一口径迭代，不跳步骤。

这段时间的重点不是先写代码，而是先把“目标行为”定义清楚，并且让文字描述、波形图、debug 引脚和测试断言保持同一份语义。

## Bug 记录工作流

每次发现 bug，都按下面这套固定流程处理，尽量不要跳步骤：

1. 先确认现象，用现有日志、打印、波形或手动观察把“实际发生了什么”说清楚。
2. 如果已有测试能稳定看到这个问题，就先记录这个测试名和运行命令；如果还没有，就补一个最小复现 testcase。
3. 在 `docs/bugs/` 下建立一条 bug 记录，按统一格式填写：
   - `Title`
   - `Status`
   - `Symptom`
   - `Expected`
   - `Repro`
   - `Related Files`
   - `Evidence` 和 `Current Hypothesis` 可以先留空，后续再补。
4. 如果当前状态值得保留，就先做一个 checkpoint commit，把“出错现场”保存下来，避免后续修复时丢上下文。
5. 接着围绕这条 bug 补充专门的复现测试，目标是让问题可以被稳定、重复地跑出来。
6. 只有在复现路径和 bug 记录都清楚以后，才开始修改 RTL 或测试基础设施去修问题。
7. 修复后必须重新运行同一条复现路径；如果 bug 消失，再把 bug 状态更新，并补充最终证据。

提交信息尽量带 bug 编号，例如：

- `FE-001 checkpoint: preserve failing reproduction`
- `FE-001 test: add dedicated repro case`
- `FE-001 fix: align request timing`

目标不是写很重的文档，而是保证每个 bug 都有：

- 明确现象
- 明确复现路径
- 明确相关文件
- 一个保留现场的提交
- 一个最终验证修复是否成功的路径
