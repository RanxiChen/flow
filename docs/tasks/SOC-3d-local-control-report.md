# SOC-3d 后续局部控制与返回边界优化

2026-10-09 用户授权四类关键路径一起修改，然后按 GitHub push/pull 流程，在 Alan 并行执行单核/四核 LiteX→Vivado，cloud_chen 完整回归。功能失败时撤销本候选物理构建。基于主仓 `9555be20a183c43728944b39fe4131493b127c28`，CVFPU `b32aeeb6eda9a61b99185e39e78b74d4fb5340be` 不变。

## 实现

- Sv39Tlb 基础页存储拆为最多 4 个 set bank × way 的无掩码本地存储；读 bank 与请求同沿锁存，响应仍下一拍。基础页、超页 PA 分别提前生成，由唯一命中独热选择；页面权限不授权候选 PA 的副作用。
- 解码 PMP 的高位比较使用 8-bit 分块和均衡字典序归约。独立旧算法、first-overlap 优先级、跨 128 B 块 carry、完整包含和权限逻辑保持。
- L1D PLRU 按常量 set 使用本地状态生成各 way 的 touch 候选；命中/安装的候选选择与最终写许可分开。保留同拍 kill、安装优先级、零位 direct-mapped PLRU，不改变命中/提交/更新拍数。
- L1IClient 的 demand/prefetch payload、error、valid 增加一拍寄存边界；支持相邻周期返回，协议接收和在途所有权仍按实际 RSPdown fire 更新，不取消已接受响应。I-cache flush 继续等待旧 miss 排空并禁止旧数据安装/交付；命中路径不增加拍数。
- Realigner firstWord/rawInst 宽 payload 按本地状态提前捕获，不由晚到 redirect/response qualification 驱动 CE；状态、有效性和 fault 元数据仍使用原授权，取消的候选不输出。
- PTW 在原 `sWait→sCheck` 接收边沿保存 leaf/pageFault 预解码；状态数、done/refill 拍数和 accessFault 优先级不变。

已同步 MMU 存储/PTW 说明、L1D PLRU 结构、集群 L1I 返回边界授权例外；frozen manifest 只更新两份实际修订合同的摘要。100 MHz、生产配置和既有数值 golden 不放宽。

## 验证覆盖与证据边界

新增/补充：PMP 各比较分块边界与跨块 access；TLB 全 bank/set/way 保留及 targeted SFENCE；L1D 各 tag bank 的 killed hit 不改变 victim；相邻 demand/prefetch 返回及 error/data 保持；真实 L1IClient+I-cache 在返回边沿/下一拍 flush 不安装旧 refill；realigner 跨字第二 parcel 与 redirect 同拍、无效输入变化、输出反压稳定。复用完整回归中的 TLB 权限/超页/PTW 异常、cache 原子/一致性/kill 和后端精确拍数断言。

本地只完成编辑、静态检查；编译/生成/仿真均按实时共享主机配置在远端执行。源 SHA、push/pull、启动命令和实际 wall time 在执行后补录。

## 基线新结果

9555be2 单核已在 Alan UTC 07:04:49.644665–08:11:13.210637 完成，exit 0、monotonic wall 3983.565155 秒；最终 soc-timing-summary.rpt 的 WNS +0.117 ns、TNS 0、WHS +0.030 ns、THS 0，bitstream 已生成。仅为单核生产布线证据，完整功能回归尚在运行，未上板。基线四核布局后 -1.457 ns，仍在布线。保留这些结果，不把中间路径排名或单核通过写成四核通过。
