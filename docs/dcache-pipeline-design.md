# Breeze 流水化 D-cache 第一版

## 目标与边界

第一版先验证“常见本地命中走流水、复杂事务走串行慢路径”是否带来可测收益，不要求兼容旧的 CPU 脉冲接口，也暂不接入 `BreezeCore`。

新模块是 `BreezePipelinedDCache`，CPU 侧使用带事务 ID 的 `Decoupled` 请求和响应。当前生产顶层继续使用旧 `BreezeDCache`；等模块行为和收益确认后，再单独修改 backend、MMU 仲裁和顶层连接。

## 快路径

- cached Load/LR 命中 `S/E/M` 时只访问本地 Tag/Data SRAM。
- 同步 SRAM 读和 Tag 比较构成流水级；响应队列不背压时，每拍可接收并完成一笔 Load hit。
- LR 命中在 lookup 后进入单项 `LrCommit` 寄存级；旧 LR 提交响应的同一拍可以接收下一笔 LR，因此 resident LR 的启动间隔也是一拍。
- 响应保持请求顺序，并原样返回请求 ID。
- 下一笔请求如果被判断为 Store、AMO、miss 或不支持的访问，流水入口关闭并转入慢路径。

## 慢路径

- Store 命中 `E/M` 在本地写入，`E` 静默转为 `M`。
- Store/AMO 命中 `S` 时发送 `GetM`。
- miss 对 Load 发送 `GetS`，对 Store/AMO 发送 `GetM`。
- 有效 victim 先发送 `PutS/PutM`，收到确认后再 refill。
- 第一版只允许一笔慢事务，不支持 hit-under-miss。

## LR/SC

- LR 复用 Load 读流水；响应进入队列时才建立 reservation。LR miss 使用 `GetS`，refill 安装完成后在响应提交点建立 reservation。
- reservation 用精确地址和访问宽度匹配 SC；probe 和 replacement 使用 32 B cache-line 粒度清除 reservation。
- SC reservation 不匹配或 cache miss 时直接返回 `1`，不分配、不访问 Home，也不写 cache。
- SC 命中 `E/M` 后进入 `ScWrite`，最终写入前再次检查 reservation；成功写入返回 `0`。
- SC 命中 `S` 时复用 `GetM` 路径，等待期间可以处理 probe。Grant 返回后 reservation 仍有效才写入；若已经失效，则返回 `1`，并把 Home 已授予的唯一副本安装为 clean `E`。
- 任意 SC 尝试、同 hart Store/AMO 成功写入、匹配 line 的 probe、reservation line replacement，以及 `reservationKill` 都会清除 reservation。

## AMO 原子窗口

AMO 命中 `E/M` 后进入 `AmoPrepare -> AmoExecute -> AmoWrite`。这三个状态构成全局 cache-local lock：停止接收 CPU 请求，允许锁存一个 probe，但必须先写入 AMO 新值，再处理该 probe。

AMO 命中 `S` 或 miss 时，等待 `GrantM` 期间不持有这个锁。`UpgradeReq/UpgradeWait` 和 refill 等待状态仍然可以处理 probe，避免 Home 等待 probe、L1 同时等待 grant 的环形等待。拿到 `M` 和最新数据后才进入本地 AMO 原子窗口。

## 当前未实现

- uncached/MMIO
- flush/maintenance
- 多 MSHR、hit-under-miss
- 与 `BreezeCore`、数据翻译和 PTW 的新接口连接

这些访问目前返回明确错误或不在新模块接口中，不能当作已经支持。

## 当前模块级证据

- 连续 12 笔已缓存 Load hit 每拍被接受，响应 ID 和数据保持顺序。
- 响应队列背压会关闭请求入口；被阻塞的 lookup 保存 SRAM 结果，解除背压后不丢失 hit，也不会产生多余 refill。
- `E` 状态 AMO 本地完成，不发送 `GetM`；等待中的 recall 得到 AMO 修改后的脏数据。
- `S` 状态 AMO 在等待 `GetM` 时可以响应 invalidate probe，随后使用 grant 携带的最新数据完成。
- Store miss 获取 `M`；Store 命中 `E` 静默升级；dirty victim 先 `PutM` 再 refill。
- resident LR 可以连续每拍进入并按序响应；最新完成的 LR 覆盖旧 reservation。
- SC 在 `E` 上本地成功，在 `S` 上通过 `GetM` 成功；无 reservation、宽度不匹配、probe 和 `reservationKill` 都会使 SC 返回失败且不写数据。
- SC 等待 `GetM` 时若 probe 杀掉 reservation，会返回失败并把 grant 数据保留成 clean `E`。

上述是 Chisel 模块仿真结果，还不是核心级 IPC、综合时序或 FPGA 运行证据。
