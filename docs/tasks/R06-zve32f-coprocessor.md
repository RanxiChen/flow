# R06：独立 Zve32f 协处理器重构

2026-10-12。权威输入为本轮用户授权：先重构独立协处理器，具体共享/banked TLB 和 SoC 挂载延后；功能目标为 Zve32f。本文替代旧完整 V 草案中本轮涉及的 FP64、全宽 FMA 和向量侧独占标量算术资源假设，不改原 R04 golden、断言、种子、规模或历史失败门槛。

## 边界与所有权

- 独立顶层包含 VRF、指令窗口/依赖、执行和写回；标量 GPR/FPR 依赖和真正的 TLB 不在此顶层内。
- 发起携带原始指令、已捕获整数/浮点标量值、vl/vtype/vstart/vxrm/frm。接收时预留窗口/判定容量；判定按序，commit 最老已判定项，kill 仅撤销未提交项。
- 默认 VLEN=DLEN=512；Zve32f 的整数元素为 8/16/32，浮点为 FP32。保留原 dot 回归，额外 64 位整数旧路径不计入 Zve32f 完成门槛。
- 每条尚在排队的指令也登记源/目的组；只有更老读/写产生 RAW/WAR/WAW。执行单元独立推进，不能让一个慢单元阻塞其他已接收的独立工作。
- VRF 物理写完成而非 write.fire 解除依赖。mask/prestart/tail 保留区域单独合并；masked-off 元素不产生异常标志。
- FMA 默认 8 个 FP32 元素/批，一行两批；转换并行度参数化。FMA 内部延迟与批次接收间隔分别报告。
- 普通浮点乘除/开方经外部 tagged FP 服务，返回含原 tag/data/fflags。顶层不假定标量 rd 唯一性、不写标量 FPR 中转。算术服务拥有接受的 token 直到响应；返回反压稳定，无未预留结果容量的发起。
- 翻译是外部 tagged 服务：VA、bytes、访问类型、上下文/epoch；返回 PA、授权长度、异常/tval 和 device 属性。当前只在 Serial 获得启动许可后发翻译，已启动请求不可取消；kill 仅作用于未启动的未提交指令。不实例化/修改 Sv39Tlb、Sv39Mmu 或 PTW。
- 精确访存与已提交算术分离。Serial 只在外部启动许可后发生副作用，最终给 Done/DoneVl/Exc；错误位置与 vstart 属于原指令。后续 SoC 接入前不把当前 AXI fatal 当作完整精确 trap。
- fflags/vxsat/标量结果以有归属的完成事件保持到接收；drained 覆盖窗口、读/写流水、外部请求及尚未接收的结果/状态。

## 阶段门禁

| 阶段 | 交付 | 证据 |
|---|---|---|
| A | 接口、窗口、RAW/WAR/WAW、写回与取消生命周期 | 对非顺序响应、反压、排队 WAR、同拍 commit/kill 的独立测试 |
| B | 并行转换、半宽 FMA、外部 FP adapter | 真实 FPnew 数值/flags 与独立参考，掩码和舍入边界 |
| C | 指令矩阵、整数/定点/mask/置换/归约补全 | 每个合法编码及非法邻域，Spike/独立算术 |
| D | 访存地址模式、外部翻译与精确重启 | 每个故障位置、跨页、迟到翻译、FOF、分段、取消与排序 |
| E | 独立协处理器组合回归 | 保留原 R04 测试和种子，新矩阵逐项闭合 |

中间阶段不得声明完整 Zve32f；仿真、综合、routed timing、SoC 和板级证据分别记账。每次硬件执行前重读共享 simulation-host.md，先预检 cloud_chen、不可用才预检 Alan；Vivado 使用 Alan。本轮不委派 agent，不修改正在重构的 SoC。

## 当前独立实现及接入契约

入口为 `design/src/main/scala/rvv/zve/ZveCoprocessor.scala`，与旧 `RvvCoprocessor` 并存。默认 512-bit VRF、16 个窗口位置、16 个转换 lane、8 个 FMA lane。这里的 lane 是 FP32 元素并行数。

| 资源/接口 | 本地所有者和容量 | 接受后保留到 | 外部责任 |
|---|---|---|---|
| issue / verdict | Window 预留指令位置，判定按序 | 普通结果入完成队列；Serial 另保留屏障 | 发起前解决 GPR/FPR RAW，捕获值与配置 |
| commit / serialGo / kill | 最老已判定未提交项；两个启动信号互斥 | 已提交项不可 kill | 普通 commit 表示不可撤销；Serial 只在标量最老、允许精确副作用时启动 |
| VRF read / returned | 每客户端 4 个返回预留，2 个读 triplet/周期 | returned.fire | 无直接异步读口；每个返回保存 tag |
| VRF write / written | 每客户端 2 个写队列，最多 4 个未确认写 | 物理 bank 写后 written.fire | 依赖释放前所有写确认已到达 |
| 本地 FP 批次 | 每引擎 8 批，独立 lane tag | batch 输出被写回端接受 | inactive lane 不发 FPU 请求，不累计 flags |
| sharedFp | slow/cross 复用，客户端号嵌入 tag，独立返回队列 | 原 tag 的 response.fire | 后续 scalar FPU 算术服务仲裁；不使用标量 rd/写 FPR 中转 |
| translation / translated | VLSU 单个有效 chunk，16-bit tag + epoch | tagged response.fire | 授权所有 bytes，返回 PA/异常/tval/PMA；后续实现 TLB/PTW/PMP |
| order / release | SoC 发放 PA 字节范围访问许可 | 成功读+VRF写确认，或 store B，再 release.fire | 与 scalar LSU/D-cache 排序，权限范围内保持一致性 |
| invalidate | store AW/W 前的完成握手 | invalidate.fire | ready 表示该 cacheline 失效已完成，不只是请求入队 |
| completion | Window 最多 16 个完成事件 | completion.fire | 更新 fflags/vxsat/标量结果/CSR；异常同拍 kill 年轻未提交项 |

正常数据流为 `Window -> 单元 mailbox -> VRF 请求/返回队列 -> 计算 -> 写队列 -> 物理 bank 写 -> ack -> 完成队列`。Window 对所有排队指令登记 VRF 源/目的，按年龄检查 RAW/WAR/WAW；同一单元按序，无依赖的不同单元允许独立完成。每个单元当前只驻留一条指令，但行/批次内部可流水；跨指令依赖按整条释放，尚无旧 dot 链的 bypass。

- ALU 保留全行并行 add/sub/and、标量逻辑右移和广播。向量逐元素移位通过窄整数单元处理。
- Conversion/非计算浮点本地并行；FMA/浮点加减本地 8-lane，512-bit FP32 行需两批输入。两批不等于两周期指令完成，VRF、FPnew 内部、写回仍各有延迟与反压。
- Integer/cross 单元用组快照支持合法重叠，复用注册乘法器、迭代除法器及逐元素置换/mask/归约；这一慢路径按多阶段逐元素执行。普通 FP32 乘除/开方及浮点归约发外部 FP 服务。
- VLSU 支持 unit-stride/strided/indexed/segmented/mask/whole/FOF，按元素和字段执行。非对齐元素拆成逐字节授权与访问；一个 load 字段的所有 chunk 成功后才写 VRF。发生异常时保留已完成元素，并报告故障元素的 vstart。当前所有非空访存采用 Serial；按冲突范围并行的优化留到 SoC 排序整合。
- Serial 屏障和 scalarMemoryBlocked 保持到最终 completion 接收，包含状态反压。drained 包含返回、写确认、FP token 和未接收状态。

`vset*`、VS/FS 状态、CSR 读写、fflags/vxsat 提交与标量依赖由后续 issuer/SoC 实现；独立入口要求配置快照。当前生成表的 330 个编码形式/144 个根操作只是译码及执行路径范围，不是 330 个逐项通过的 conformance 证明。旧自定义 dot 编码与 golden 留在原 R04 路径；本轮不把它们混入 Zve32f 标准指令。

现有数据接口为独立 AXI。实际挂载到哪个 L2/互连、如何与 D-cache 保持一致性，要由后续 SoC 接入确认。此独立顶层没有直接实例化 cache 或 L2。
