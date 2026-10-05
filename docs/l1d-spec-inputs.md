# L1D spec 输入摘录

2026-10-06 由原始文档摘出，只含事实与已同意决定，供写 `l1d-rtl-spec.md` 使用。缩写：DP = `dcache-pipeline-design.md`，CS = `coherence-l2-rtl-spec.md`，MS = `breeze-mmu-rtl-spec.md`，CL = `breeze-mmu-closure-checklist.md`，BP = `backend-pipeline-design.md`，IN = `v1-integration-notes.md`，OB = `observability-design.md`，MEM = 记忆 `breeze-dcache-l2-redesign-decisions`（后写覆盖先写）。

## 1. 几何与参数
| 项 | 值 | 出处 |
| --- | --- | --- |
| 容量/路/行 | 16 KiB / 4 路 / 32 B，128 set；offset [4:0]，index [11:5]，全在页内 → VIPT 无别名，PA tag 判命中 | DP §1、MEM 10-05 r3 |
| MSHR | N=1（CPU 与 PTW 共用，结构按 N 参数化）；写回槽 1；pending-store 1 项 | DP §2.4、§2.2 |
| PA | 缓存/协议内部 32 bit；MMU 出 56 bit，≥2^32 由 PMA 判 access fault | IN §3、CS §0.2 |
| 链路 | 4 条：REQ、RSP↑、SNP、RSP↓；数据 256 bit，每消息单拍 | CS §0.2、§1.2 |
| Data SRAM | 字节写使能，普通 Store 不读改写；端口组织在 RTL spec 定，不依赖同址读写返回值 | DP §2.1 |

## 2. 流水与后端对齐
| 级 | L1D 职责 | 后端级 | 出处 |
| --- | --- | --- | --- |
| S0 / T | 入口仲裁；Load 的 S0 冲突检查；发 dTLB + VIPT Tag/Data 读；保存请求；接收前预留 TLB 结果容量 | EX（兼 AGU，`rs1+imm` 同拍入 S0；完整 64 bit 送 dTLB） | DP §2.1、BP §3 |
| S1 / T+1 | 收翻译/页权限；各路预选 8 B 字；保存 PA/tag/数据；TLB miss → 翻译等待 | MEM | DP §2.1 |
| S2 / T+2 | PA tag 比较、命中路选择、Load 格式化；PMP/PMA；快照失效检查；与 MSHR 同行检查；判定与数据组合送 WB（同 Rocket），WB 拍末写回 | WB（提交点） | DP §2.1、BP §3–4 |
| pending-store / T+3 | 按 mask 写 Data，E→M；写口受阻则保持 | — | DP §2.1 |
- 时序退路（DP §2.1）：① PMP/PMA 移到 S1 寄存进 S2；② 仅 Load 数据寄存一拍（load-use 3），判定仍在 S2，提交点不变。
- **S2 判定即提交**：给出"命中完成/进入 MSHR"时翻译、页权限、PMP/PMA 已全部通过，此后无精确异常（DP §2.3、BP §4）。
- 判定类型（DP §2.9）：命中完成（Load 带数据 / Store 已入 pending-store）；进入 MSHR（已提交）；异常（kill 年轻请求）；暂不判定（TLB 等待、MSHR 同行、MSHR 满、写回槽未空，后端停在 WB）；迟到数据（回放时给出 rd 标签+数据+错误位，valid/ready，后端不能写时 L1D 保持）；LR 命中 E/M 与 SC 在 S2，LR miss 在回放完成时；AMO/MMIO 在实际完成时（不提前提交）。
- 每请求按程序序恰好一次判定；v1 至多 1 笔迟到数据在途（DP §2.9）。
- 后端写口：长延迟优先，固定序 L1D 迟到数据 > DIV > MUL > FPU，落选者保持（BP §6）。refill 错误：后端清记分板位并上报 `hartFatal`（BP §7）。
- 异常/kill（DP §2.3）：S1 页异常、S2 PMP/PMA 异常 kill 年轻请求及其未生效写；kill 不撤销已发 Get/Put、已收 Data/Ack、须答复的 probe。Load/LR 用 Load 类异常，Store/SC/AMO 用 Store/AMO 类。非对齐在入 dTLB 前由 LSU trap（CL C2、MS §3.1）。

## 3. S0 保守冲突检查（DP §2.2）
- Load 用 VA[11:3] 比较所有更老、尚未写入 Data 的 Store：S1/S2 用随请求寄存的 VA[11:3]，pending-store 用 PA[11:3]；同 8 B 字即停在 S0，不发读，不 forwarding。
- 只误报不漏报；整字保守（mask 不重叠也停）；解除条件 = 已写入（写入拍仍算冲突，下一拍才可读）；写口受阻继续停。
- 无需作废/重放；PTW 的 PTE 读同样检查（用自身 PA[11:3]）；MSHR 回放也经此检查（DP §2.4）。
- 资源：≤3 个 9 bit 比较器。停顿信号同拍反压 EX/前端；时序退路：寄存一拍，S1 kill 后从 S0 重发（BP §3）。
- probe/refill/权限变化使在途快照失效：标记失效，S2 作废并从 S0 重查，年轻的 S1 请求一并重查（DP §2.2 末段）。

## 4. MSHR、hit-under-miss、refill（DP §2.4）
- MSHR 字段：行 PA、GetS/GetM、refill way、请求类型；Load 另记 rd 标签（整/浮+编号），Store 另记 8 B 数据+mask。
- Load miss → GetS（L2 无共享者时回 DataE，MEM r3 "Load miss grants E"）；Store miss / S 写升级 → GetM；L2 决定回 AckE 还是 Data。
- 分配在 S2，需 MSHR 空闲且写回槽可用；分配时选 victim：有效则立即经 RSP↑ 发 Put（任何状态都发，含 S/E clean，CS §1.5；M 带整行），写回槽记地址至 PutAck；该 way 置无效并锁定（不命中、不再选）。写回槽未收 PutAck 时不能分配需 Put 的 MSHR → 暂不判定。
- 写升级不选 victim，锁原 way；等待中被 Inv 撤销则 L2 回 Data，装回同 way。
| S2 请求 | 处理 |
| --- | --- |
| 与 MSHR 同行（任何类型） | 暂不判定，停住；回放完成后从 S0 重查，不合并 |
| 他行命中且权限够 | 正常完成 |
| 他行 miss/需升级 | 暂不判定，MSHR 释放后重查 |
| PTW PTE 读 | 同规则；miss 用 MSHR，PTW 等数据，不提前提交 |
| 非 aq/rl LR/SC | 流水内执行（§7） |
| AMO、aq/rl LR/SC、MMIO | 等 MSHR 与 pending-store 空 |
- 停住的请求按年龄留在流水内，年轻的不越过。
- refill：Data/Ack 完整收到 → 作内部请求从 S0 写 Tag/Data、解锁 way → MSHR 回放（PA 入 S0，不查 dTLB）：Load 出迟到数据，Store 按 mask 写；回放完成释放 MSHR（"至少完成一次"）。
- **S0 仲裁序**：probe 处理、refill 安装 > MSHR 回放 > PTW > 停住请求重查 > CPU 新请求（DP §2.4，§2.5.3 一致）。
- 错误：RSP↓ `error=1` 时不安装；Load 迟到数据带错误位，Store 数据丢弃；已提交 → 非精确，v1 `hartFatal`。
- MSHR 等待不阻塞：RSP↓ 接收与安装、RSP↑ 发送、无关 probe、已收 PTW 请求的完成。

## 5. TLB miss 与 PTW
- dTLB `TlbPortIO`：req Decoupled{vaddr 64, cmd Load/Store}，resp Valid（fire 后一拍，无 ready）{hit, miss, pageFault, accessFault, paddr 64}，恰一位为 1；`kill`（MS §4.2）。`req.ready = !missValid && !sfence.valid && !sfS1Valid && !kill`，不依赖比较结果（MS §5.4）。LR 发 Load，SC/AMO 发 Store（MS §3.1）。
- `dropS1Next = s1Valid && miss`：miss 同拍 fire 的年轻请求被丢弃、无 resp；请求方须在 ready 恢复后按程序序重发 miss 请求及其后者（MS §5.3、CL C9）。pendingFault 时 ready=1，重发同 VPN 得 fault（MS §5.4）。
- 翻译等待 2 项 FIFO：X（miss）+ 被 drop 的 Y，显式跟踪 Y；次拍关 CPU 入口，老 S2/pending-store/MSHR 继续；等待请求不占 MSHR、不锁阵列；恢复后按年龄从 S0 重查，可再 miss（DP §2.5）。
- PTW 独立物理入口在 S0 仲裁（优先于重查与 CPU 新请求），绕 dTLB 但查 cache，S 特权 PMP/PMA，8 B 对齐读，可用 MSHR；响应独立路由（DP §2.5）。
- `PtwMemIO`：req Decoupled{paddr 56}（MMU 在 ready 前保持），resp Valid{data 64, accessFault}，单拍不反压；接收后恰一个 resp（MS §4.5、CL C6）。PTW 已接受的 walk 不因 kill 丢弃（MS §5.4）。
- 前进：TLB miss 请求不占 D-cache 资源；MSHR 只被与翻译无关的已提交请求占用（CL C7、DP §2.5）。miss 时序参考 MS §9.2（PTE 读延迟 L≥1）。
- `idle` 输出（MS §4.4）供 SFENCE 序列使用。

## 6. probe（DP §2.6、§3.3）
- SNP {op Inv/Down, owner 角色, addr}；有独立接收/保持寄存器；按当前 tag/权限处理后发 InvAck/DownAck，本地 M 带整行（E 可能已静默成 M）。Down 恒 owner 角色。
- 只两种压住：① 命中 MSHR 等的行且角色 > 本地状态（本地 I 收任意 probe；本地 S 收 owner 角色）→ 安装并回放完成后按新状态答复（PTW 请求已 kill 时安装后即解除）；② 命中写回槽未收 PutAck 的行 → PutAck 后答不带数据的 Ack。
- 本地 S 正在 GetM 升级时收到 Inv(sharer)：立即失效 S 并答复，继续等 GetM（L2 将回 Data）。
- 写回槽中的行在 PutAck 前不得再发 GetS/GetM。Put 与 probe 不合并，各自完成。
- probe 与 pending-store 同行：按年龄/本地仲裁定序；老 Store 已进入生效步骤则先写完再让 probe 取新值；权限先被撤销则未生效 Store 重取权限后重查；禁止"已答失效后旧 Store 再写"。具体同拍仲裁由 RTL spec 定。
- 每核至多 1 个未答复 probe（L2 也保证）；压住期间 SNP 对该核反压。AMO 短窗口、LR 80 拍窗口、pending-store 同行仲裁属有限拍本地延迟，不算压住（DP §2.6、CS §1.4）。

## 7. LR/SC 与 AMO（DP §2.7）
- 非 aq/rl LR：Load 类检查；本地 E/M 则 S2 完成、提交时建 reservation；本地 S/I 一律 GetM、分配 MSHR、不提前提交，回放后完成并建 reservation。每 hart 一份，记物理行地址。
- SC 永不 miss：Store 类检查（权限异常不能吞成失败）；S2 判定：reservation 有效且地址匹配 → 经 pending-store 写、返回 0；否则返回 1，不写不访 L2。每次 SC 后清 reservation。
- reservation 清除：该行任何 probe、该行被替换、本 hart 陷入、SC 执行。LR 后 80 拍窗口内压住命中该行的 probe，窗口在 SC/陷入/到期结束。
- aq/rl：rl → 等 MSHR 与 pending-store 空再入流水；aq → 年轻访存等它完成。
- AMO：无论 aq/rl 独占通路：停年轻、排空老（MSHR+pending-store 空），检查权限，取最新数据与独占权；等 Data 期间不持锁、照常处理 probe；短窗口只覆盖取得权限后的本地读改写，期间冲突 probe 可反压；不提前提交；结果容量在进窗口前保证。AMO.W 字节范围与返回扩展由 RTL spec 定。

## 8. MMIO（DP §2.10）
- 识别：S2 PMA 判 device/不可缓存；发出条件：已在 WB（最老）且 MSHR+pending-store 空；每核阻塞 FSM，期间整条流水停。
- AXI4-Lite 64 bit，1/2/4/8 B 用 WSTRB；读数据按 addr[2:0] 移位+符号扩展（复用 Load 格式化）。集群 `mmio` 全局 1 笔在途、核间轮转。
- 响应：OKAY 完成；SLVERR/DECERR → load / store access fault（精确）。Load 收数据、Store 收 B 才退休，无 posted write。
- 发出后不可取消，中断/调试暂停等它退休。超时依赖 LiteX（返回全 1 不报错），调试 watchdog 只记事件。
- 不支持（按 §11 原子性 PMA）：LR 访问 I/O → load access fault；SC、AMO → store/AMO access fault；PTW 访问 I/O → access fault；非对齐已在 LSU trap。

## 9. 栅栏（IN §3、BP §7、DP §2.7）
- FENCE：作为 L1D 请求，MSHR 与 pending-store 均空时完成；只限 CPU 普通请求，PTW 与协议完成照常前进。
- FENCE.I：后端在 WB 等 L1D MSHR+pending-store 空 → 清 L1I、重定向；L1D 不写回不失效，删除 `dcacheFlushReq/Done`（IN §3 覆盖 BP §7"沿用现有语义"）。
- SFENCE.VMA：WB 串行：更老指令完成、前端 kill → 等 L1D MSHR+pending-store 空 → 等 MMU idle → 一拍 sfence → 等 idle → 重定向（IN §3、MS §4.4、CL C4/C5）。L1D 需导出"MSHR 与 pending-store 均空"。

## 10. 消息编码速查（CS §1.2，照抄）
| 链路 | 字段 | op |
| --- | --- | --- |
| REQ（可反压） | op 2, addr 27（行地址 = PA[31:5]）, id 1（L1D 恒 0）, mask 32, data 256 | 0 GetS, 1 GetM, 2 Read, 3 MaskWrite；L1D 只发 GetS/GetM |
| RSP↑（L2 恒收） | op 2, hasData 1, addr 27, data 256 | 0 Put, 1 InvAck, 2 DownAck；hasData：Put 时行为 M，Ack 时本地为 M |
| SNP | op 1, owner 1, addr 27 | 0 Inv, 1 Down（owner 恒 1） |
| RSP↓（客户端恒收） | op 3, id 1, error 1, data 256 | 0 DataS, 1 DataE, 2 AckE, 3 PutAck, 4 ReadData, 5 WriteAck；L1D 只收 0–3 |
- 握手：valid/ready，发送方 valid 拉高后 fire 前保持所有字段（断言）；RSP↑/RSP↓ 接收端 ready 只可因有限拍本地仲裁为 0（CS §1.3）。L1D 在途：Get 1、Put 1；L2 RSP↓ 中 PutAck 与 Data/AckE 可同时排队、按写入序发出（CS §1.1、§8）。
- 纪律（CS §1.4）：① RSP↑、RSP↓ 接收不依赖任何消息；② SNP 接收只依赖 RSP↓（MSHR Data/Ack、写回槽 PutAck）与有限拍本地延迟；③ REQ 接收可依赖一切。L1D 的 SNP ready 归类由 L1D spec 给出（CS §9）。
- 链路内 FIFO，跨链路无序；L2 在 Data/AckE 入输出缓冲即释放保护，新 probe 可能先于 Data 到（DP §3.1）。协议错误由 L2 断言：GetS 命中自己 UNIQUE/已是 sharer、GetM 时已是 owner（CS §4.4）。

## 11. PMP / PMA
- 位置：L1D S2 用最终 PA；数据访问用有效特权（MPRV 时 Load/Store 用 MPP，同 MS §5.3 `effPriv`）；PTW 读用 S；各一份实例（IN §3、CL C8、DP §2.5）。
- 原子性 PMA（IN §3、DP §2.7）：json 每区域加 `mainMemory`、`amo`、`reservability`，`PMAChecker` 加 `amoOk`、`rsrvOk`。main_ram、sram = AMOArithmetic + RsrvEventual（满足 Ziccamoa、Ziccrse）；ROM 与设备区 = AMONone + RsrvNone（ROM 归 I/O，可缓存）。S2：LR→RsrvNone 报 5、不发 GetM；SC→RsrvNone、AMO→AMONone 报 7，SC 不看 reservation。设备树加 `_ziccrse_ziccamoa` 待 LR/SC 争用与 AMO 测试通过。
- 异常类别依据：特权规范 mcause 一节，LR 为 Load 类、SC/AMO 为 Store/AMO 类；Rocket `TLB.scala:580-596`+`RocketCore.scala:738-745`、Spike `mmu.cc:248`/`mmu.h:287-292` 一致。
- `PMAChecker`：in `query{addr 64, sizeLog2 3, accessType Fetch/Load/Store}`，out `result{regionHit, allowed, cacheable, device}`；纯组合，越 `addressWidth`（32）与空洞默认拒绝，可查整行 sizeLog2=5（PMAChecker.scala）。
- `BreezePmpChecker`：in `addr 64, sizeLog2 3, access BreezeMmuAccess, privilege, context BreezeMmuContext`，out `allowed`；8 项生效，TOR/NA4/NAPOT；privilege 由调用方给（有效特权）。
- `BreezeMmuContext`：satp, privilege, mprv, mpp, sum, mxr, adue, pmpcfg[], pmpaddr[]（interface.scala）。
- `BreezeAmoAlu`：in `func BreezeAmoFunc, isWord, oldOperand 64, rs2 64`，out `newOperand 64`；`BreezeAmoFunc` = Swap, Add, Xor, And, Or, Min, Max, MinU, MaxU, PteSetAd（Svade 下 PteSetAd 不再使用，IN §3）。
| 区域 | 起始 | 大小 | R/W/X | 可缓存 | device |
| --- | --- | --- | --- | --- | --- |
| machine_timer | 0x0200_0000 | 64 KiB | RW- | 否 | 是 |
| plic | 0x0C00_0000 | 64 MiB | RW- | 否 | 是 |
| boot_rom | 0x1000_0000 | 64 KiB | R-X | 是 | 否 |
| linux_boot_rom | 0x1001_0000 | 64 KiB | R-X | 是 | 否 |
| sram | 0x1100_0000 | 64 KiB | RWX | 是 | 否 |
| litex_mmio | 0x1200_0000 | 16 MiB | RW- | 否 | 是 |
| main_ram | 0x8000_0000 | 2 GiB | RWX | 是 | 否 |

## 12. 计数器事件（OB §2.3，L1D 只引出事件线）
`load_access` `load_miss` `store_access` `store_miss` `upgrade` `ptw_access` `ptw_miss` `hit_under_miss` `mshr_busy_cycles` `mshr_full_stall` `same_line_stall` `s0_conflict_stall` `writeback_dirty` `writeback_clean` `probe_received` `probe_held_cycles` `lr_count` `sc_fail` `mmio_read` `mmio_write` `mmio_cycles`

## 13. 已发现的矛盾与处理（2026-10-06 已统一）
| # | 矛盾 | 结论 |
| --- | --- | --- |
| K1 | DP §1"L1D 不做 hit-under-miss" vs §2.4 | 已改 DP §1：1 MSHR hit-under-miss |
| K2 | DP §2.10"LR 访问 I/O 报 store/AMO fault" vs 规范 | 已改 DP §2.3/§2.10：LR 报 Load 类，SC/AMO 报 Store/AMO 类 |
| K3 | 只读可缓存 ROM 上的 LR/SC/AMO 无定义 | 已加原子性 PMA（DP §2.7、IN §3），见 §11 |
| K4 | BP §7"FENCE.I/SFENCE.VMA 沿用现有语义"是 10-05 旧核心语义 | 已改 BP §4/§7 为 IN §3 的 WB 串行语义；A02 只适用旧路径 |
| K5 | DP §2.1"T+3 可见" vs BP WB=S2 | 已改 DP §2.1：S2 组合送 WB，两级退路 |
| K6 | MS §4.2"寄存器驱动" vs §5.3 `s1Valid && !kill` | MS 不改；L1D spec 写明：kill 当拍 L1D 自行作废 S1，kill 不得依赖当拍 `resp.valid`（Rocket 同为组合 kill，`DCache.scala:271`） |
| K7 | DP §2.1 两张旧图 | 已标弃用，以正文为准 |
| K8 | DP §7 L2 II/链路宽度 | 以 CS §0.2 为准 |
| K9 | 记忆早期 8 KiB/TileLink | 已被后续更新覆盖 |
