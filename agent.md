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

## 当前验证约定

当前前端模块的生成入口是：

```bash
cd /home/chen/FUN/flow/design
sbt "runMain flow.frontend.FireBreezeFrontend"
```

以后如果要验证 `BreezeFrontend` 当前是否还能正常展开，优先运行这个入口，而不是只做普通 compile。
