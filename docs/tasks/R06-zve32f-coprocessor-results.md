# R06 独立协处理器交付与证据

2026-10-12。独立实现位于 `flow.rvv.zve`，具体 TLB 和 SoC 挂载留到后续重构。契约见 [R06-zve32f-coprocessor.md](R06-zve32f-coprocessor.md)。

## 源码与执行位置

- 分支：`feat/rvv-zve32f-coprocessor-20261012`。
- 隔离 worktree：`/home/chen/leisure/flow/.worktrees/rvv-zve32f`；基线 `43d2ea0`。
- 组合功能回归源码：`d1aa0c0edd11e23f9bc081dceeadb190bf3873be`。
- 生产顶层与最终配置限制源码：`14ccd6fa1c8adf91a0ee8f6e3dfab2e1040470c0`。
- 最终代码快照：`93d7c103a372ff83de51764fd121273098bf453b`；相对上项只改正一个新增配置测试的编码，生产 RTL 源码一致。
- 每次执行均重新读取主 worktree 的 `docs/cross-project/simulation-host.md`；cloud_chen SSH 连接超时后预检并使用 Alan，没有将 RTL/golden 失败当作换主机理由。
- 实际远端 cwd：`/home/chen/FUN/flow-rvv-zve32f-20261012/design`。Java 11.0.32.1、Verilator 5.028，限制两个处理器线程、低优先级运行。
- 未修改主 worktree 的 SoC/后端源码。旧 RVV RTL、FP wrapper、R02/R03/R04 golden、随机种子及合同保持原文件；源码差异检查为零。本轮未重跑旧 R04 的 1000-seed/P6 性能合同。

## 交付结构

| 模块 | 当前行为 |
|---|---|
| `ZveCoprocessor` | 生产顶层，默认 VLEN=DLEN=512，6 条执行路径；外部翻译、内存排序、共享 FP 服务 |
| `ZveWindow` | 16 个位置，从接受开始记录源/目的，按序判定/启动，RAW/WAR/WAW、每单元 mailbox、独立完成、Serial 最终状态屏障 |
| `ZveVrfAccess` | 复用原 VRF，注册仲裁、返回容量预留、每次最多两组 triplet 读，物理写确认 |
| `ZveAluVectorEngine` | 整行 add/sub/and、标量右移和广播，注册结果队列和 byte enables |
| `ZveFpVectorEngine` / batch pipeline | 16-lane 转换/非计算浮点、8-lane FMA/加减；独立 tag、掩码/prestart/tail 保留、flags 汇总 |
| `ZveElementEngine` / integer arithmetic | 注册乘法、迭代除法、定点舍入与饱和、mask、置换、归约、估算指令；组快照允许合法重叠 |
| `ZveSlowUnit` / shared mux | 普通 FP32 乘除/开方和浮点归约复用外部 FP 服务；客户端 tag 空间与响应队列独立 |
| `ZveMemoryEngine` | unit/strided/indexed/segment/mask/whole/FOF；授权具体 chunk、PA 访问许可、AXI、物理写确认、异常/vstart/VL 状态 |

本地转换直接实例化注册化 `fpnew_cast_multi`：FP32-only 的通用 `fpnew_top` 转换封装会按浮点格式宽度过滤掉 INT16；直接实例化显式保留 INT16/INT32，未添加 FP16 ALU，也未修改 CVFPU 子模块。非计算浮点与转换结果仲裁在反压时锁住选中结果。

一行 FP32 FMA 需要两批输入；这不是两周期整条指令完成的性能承诺。当前一个执行单元驻留一条指令，行/批次内部可流水，跨指令依赖按整条释放。整数/置换的窄路径和访存的逐元素路径仍有多周期启动/执行成本。

## 已核验的功能证据

组合回归：**25 tests / 13 suites，failure=0、error=0、skip=0，exit=0**。XML 与请求的 suite 名逐项匹配，未将其他旧 XML 计入结果。

证据根：`records/r06/d1aa0c0e-final-functional-20261012T040215Z/`；远端相对根同名，位于 `/home/chen/FUN/flow-runs/rvv-r06/`。`provenance.json`、`remote/command.txt`、`remote/sha.txt`、`remote/exit-code.txt`、`remote/test.log`、XML 与 `audit.json` 保存执行身份和审计。

| suite | tests | 有效范围 |
|---|---:|---|
| ZveWindowSpec | 2 | 排队 WAR、独立单元、同拍 commit/kill、完成状态反压 |
| ZveFpBatchSpec | 1 | 非顺序 lane 返回、inactive lane、批次输出反压 |
| ZveVrfAccessSpec | 1 | triplet 仲裁/归属、物理写确认、返回反压 |
| ZveDecodeSpec | 1 | EEW/EMUL、混合宽度、非法 FP64/重叠/掩码 |
| ZveIntegerArithmeticSpec | 2 | 8/16/32 位边界、高位乘积、4 种定点舍入、除零/溢出/除法反压 |
| ZveFpArithmeticSpec | 1 | 真实融合精度 FMA、inactive inf×0 不置 NV |
| ZveFpEstimateSpec | 1 | 规范估算示例、NaN/Inf/zero/overflow 与 flags |
| ZveConversionSpec | 2 | INT16/32 负数、F2I 饱和/NV/NX、混合转换/非计算响应 tag 与反压 |
| ZveCoprocessorSpec | 1 | 真实 VRF 全回路、mask/permute/widen/narrow、FP乘除/开方/归约；独立 ALU 越过慢 DIV，依赖 ALU 等待物理写回 |
| ZveMemorySpec | 1 | 随机反压、非连续跨页 PA、非对齐、负/零 stride、index8/16/32、NF2/NF8、mask/whole、FOF 首/后故障、bus error、重启、无部分字段发布 |
| RvvFrontendSpec | 8 | 原前端生命周期/Serial/age-wrap 回归 |
| RvvRegisterFileSpec | 1 | 原 v0 shadow 与 bank 仲裁回归 |
| RvvSmokeSpec | 3 | 原 512/512、256/256、512/256 配置编译/复位回归 |

生产阶段证据：`records/r06/14ccd6fa-production-20261012T040819Z/`。`GenerateZve` 生产 RTL 生成 exit=0；无 debug VRF、无本地慢 FPU 的生产顶层编译/复位测试通过，完整协处理器回路亦通过。该轮整体 test exit=1，因为新增 mf8 测试误用了 mf2 的编码 7；失败 XML/log 完整保留，没有调整非法配置的预期或 RTL 来迁就测试。

改正 mf8 的编码为 5 后，最终阶段 `records/r06/93d7c103-decode-final-20261012T041343Z/`：生产 RTL 重新生成 exit=0，译码测试 1/1，failure/error/skip 均为 0，test exit=0。该阶段仅收集本轮新生成的 XML。两轮生产 RTL 的全部 SystemVerilog 文件逐字节哈希一致，`audit.json` 记录了等价检查和上轮故障的闭合原因。

交付文档提交不改变已验收的主/测试源码和依赖；源码等价记录保存在最终阶段 evidence 中。所有失败阶段仍保留在 `records/r06/`，没有删除失败证据。

## 后续验收范围

- 330 个编码形式、144 个根操作的表与执行入口已形成，但未取得逐项合法/非法邻域和全部 IEEE 舍入/异常组合的 conformance 证明；不能据此宣布完整 Zve32f 已验收。
- `vset*`、VS/FS、CSR、标量 GPR/FPR 依赖与结果提交由 issuer/SoC 接入。仿真 shell 的真实共享 FPU 仅替代外部服务，不代表标量后端共享仲裁已实现。
- 具体多 bank TLB、PTW/PMP/PMA 实现、scalar LSU/D-cache 一致性/排序、L2 挂载仍在外部边界。当前非空向量访存均用 Serial；仅有地址冲突时阻塞的细粒度优化尚待 SoC 接入。
- 原自定义 dot 及其 bypass 留在旧 R04 顶层，未移植进新标准 Zve32f 顶层。
- 本轮无综合、资源、routed timing、bitstream 或板级证据，也无新流水的端到端 II/吞吐合同结论。不能沿用 R04 的 PPA 数字描述本实现。

## 重现入口

在按共享主机配置完成预检的远端专用 checkout 中，激活 Flow 环境：

```text
cd design
sbt 'runMain flow.rvv.zve.GenerateZve generated/rvv-zve32f'
```

生产 RTL 不含 debug VRF 或本地慢 FP 单元。编译包含 CVFPU 聚合源码与 `ZveFpnewWrapper.sv`，需要 `third_party/cvfpu/src/common_cells/include` 的头文件目录，以及已初始化的 CVFPU 子模块源码。实际 Alan 入口使用证据中的 sbt-launch 1.9.7 jar，不假定 PATH 中有 sbt。

本地编排入口 `python3 rvv/r06/run-remote.py <stage> <explicit-suites...> --rtl` 会重新读取主 worktree 的当前主机配置，先预检 cloud，再预检备用机；仅运行已提交的准确 SHA，并保存生产 RTL 哈希、命令、日志和新生成的 XML。
