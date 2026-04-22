# Flow Agent Notes

本文件记录当前仓库里前端、后端与联调相关的真实状态，避免继续沿用已经过时的阶段性描述。

## 当前实现状态

当前已经具备的主路径如下：

1. `BreezeFrontend` 已经接入 `BreezeCache`，并通过 `nextLevelReq/nextLevelRsp` 与下一级存储接口联通。
2. `BreezeCore` 已经把前端、fetch buffer、后端串起来，形成完整取指到执行的基本通路。
3. `BreezeBackend` 已经会根据实际执行结果产生 `frontendRedirect`，用于修正前端 PC。
4. 前端和 cache 相关调试口已经接出，前端/核心测试里也已经在使用这些观测信号。

这意味着当前工作重心不再是“把 cache 接进前端”或“只搭骨架”，而是继续补齐行为、修正时序、扩测试覆盖。

## 前端当前行为

当前 `BreezeFrontend` 的实现特点如下：

1. 地址统一按虚拟地址处理。
2. 前端内部按 `S1/S2/S3` 组织请求、返回和输出寄存。
3. `S1` 负责向 `BreezeCache` 发起取指请求。
4. `S2` 跟踪已经发出的 cache 请求，并等待返回。
5. `S3` 锁存返回的 `pc/inst`，再通过 `fetchBuffer` 送给后端。
6. 当前支持把后端 redirect 和前端本地 fast redirect 一起并入 next-pc 选择。

## 分支与跳转现状

当前仓库里的分支/跳转语义应按下面理解：

1. 默认配置下，`BreezeFrontendConfig.branchPredCfg = NoBranchPredictorConfig`。
2. 也就是说，默认前端不做分支预测，不默认在前端主动跳转。
3. 默认取指路径按 `pc + 4` 顺序推进。
4. 真正发生跳转修正时，由后端执行结果产生 `frontendRedirect`，前端收到后刷新并跳到目标地址。
5. 如果显式启用 `GShare` 分支预测配置，当前前端已经具备一套 gshare 雏形：
   - `S1` 已接入 `GHR + BTB + PHT` lookup
   - 预测元数据会沿 `S2/S3` 流水推进，并传到后端
   - `S3` 已接入 `MiniDecode` 的快速修正路径
   - 后端已经具备 `BTB/PHT/GHR` 的反馈更新通路
6. 当前工作的重点已经从“是否有 gshare 骨架”转为“验证这套 gshare 实现的语义是否正确、时序是否符合预期”。
7. 因此，现在不要再把当前系统描述成“只有 JAL fast path”，更准确的说法是“已经实现 gshare 雏形，但还需要系统性验证正确性”。

一句话说，当前默认行为就是：

`不预测条件分支，顺序取指，跳转/分支修正主要依赖后端 redirect。`

如果显式启用 `GShare`，则应理解为：

`前端已经具备 gshare 预测、S3 快速修正和后端训练反馈的基本闭环；下一步重点是验证正确性。`

## 当前验证入口

当前常用验证入口如下：

```bash
cd /home/chen/FUN/flow/design
sbt "runMain flow.frontend.FireBreezeFrontend"
```

```bash
cd /home/chen/FUN/flow/design
sbt 'testOnly flow.frontend.BreezeFrontendSpec'
```

```bash
cd /home/chen/FUN/flow/design
sbt 'testOnly flow.core.BreezeCoreSpec'
```

如果目标是确认前端与核心主路径没有被改坏，优先跑这些入口，而不是只看单纯能否 compile。

当前阶段的项目优先级应按下面顺序推进：

1. 先实现 `ecall`，补齐最基本的异常/程序退出通路，保证后续测试程序能够被正确承载。
2. 在 `ecall` 打通之后，接入 `riscv-tests`，优先验证 `I` 指令集语义是否正确。
3. 只有在 `riscv-tests` 对 `I` 指令集的验证结果稳定之后，才启用 `GShare` 配置，去验证分支预测路径本身的正确性。

因此，当前不要把 `gshare` 验证当成最前面的工作项。更准确的说法是：

`gshare` 验证是后续阶段工作，它依赖 `ecall` 和基础 ISA 验证先收敛。

## 文档维护原则

后续更新本文档时，按下面规则处理：

1. 只保留“当前真实状态”和“当前仍然有效的协作约定”。
2. 明显带有阶段性的临时流程，完成后就删，不长期堆在这里。
3. 如果实现已经改变，优先先改本文档，再继续口头沿用旧说法。
4. 不要把“计划做什么”写成“已经实现了什么”。

## 相关文件

- `design/src/main/scala/frontend/BreezeFrontend.scala`
- `design/src/main/scala/backend/BreezeBackend.scala`
- `design/src/main/scala/core/BreezeCore.scala`
- `design/src/test/scala/frontend/breezefrontendSpec.scala`
- `design/src/test/scala/core/breezecoreSpec.scala`
