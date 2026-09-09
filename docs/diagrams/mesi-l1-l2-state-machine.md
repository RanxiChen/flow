# MESI L1/L2 缓存一致性状态机（当前设计）

来源：`MESI_L1_L2_State_Machine_Current_Design.docx`。只画**已讨论定稿**的部分，L2 完整目录状态机、并发/MSHR 等未展开内容不擅自补全（对应文档 §10）。

## 1. L1 CPU 请求主 FSM

```mermaid
stateDiagram-v2
    [*] --> S_IDLE

    S_IDLE : 等待 CPU Load/Store；L2 一致性消息入口
    S_LOOKUP : 按 index 发起 Tag/Data SRAM 读
    S_COMPARE : 比较所有 way tag + MESI!=I 定 hit；miss 选 victim
    S_EVICT : 检查 victim MESI
    S_WB_WAIT : 等待 L2 确认 victim 写回
    S_ALLOCATE : 向 L2 发 miss 请求（GetS/GetM）
    S_WAIT_RESP : 等 L2 Data Resp / 权限响应
    S_REFILL : 安装 line；Store miss 合并 store data
    S_CHANGE : Store Hit：写 Data SRAM + 更新 MESI
    S_OUTPUT : 向 CPU 返回 Load 数据
    S_DONE : 向 CPU 表示 Store 完成

    S_IDLE --> S_LOOKUP : CPU Load/Store（锁存 addr/type/data）
    S_LOOKUP --> S_COMPARE : 下一拍（SRAM 读结果）
    S_COMPARE --> S_OUTPUT : Load Hit M/E/S（无消息）
    S_COMPARE --> S_EVICT : Load/Store Miss（含 tag+I）
    S_COMPARE --> S_CHANGE : Store Hit M/E（E→M，静默）
    S_COMPARE --> S_WAIT_RESP : Store Hit S（GetM/Upgrade，loose end）
    S_EVICT --> S_ALLOCATE : victim I/E/S（clean）
    S_EVICT --> S_WB_WAIT : victim M → WriteL2
    S_WB_WAIT --> S_ALLOCATE : 写回完成
    S_ALLOCATE --> S_WAIT_RESP : 握手完成（Load→GetS / Store→GetM）
    S_WAIT_RESP --> S_REFILL : Data Resp（miss 路径）
    S_WAIT_RESP --> S_CHANGE : 升级授予（Store Hit-S）
    S_REFILL --> S_OUTPUT : Load：安装完成
    S_REFILL --> S_DONE : Store：安装+合并完成
    S_CHANGE --> S_DONE : 下一拍
    S_OUTPUT --> S_IDLE : 输出完成
    S_DONE --> S_IDLE : 完成脉冲
```

## 2. L1 L2 主动路径（Invalidate + GetData，共享 S_IDLE）

```mermaid
stateDiagram-v2
    S_IDLE : 共享入口（接收 L2 主动消息）

    S_IDLE --> S_INV_LOOKUP : L2 Invalidate(addr) 锁存
    S_INV_LOOKUP --> S_INV_CHECK : 下一拍
    S_INV_CHECK --> S_INV_ACK : miss / MESI=I
    S_INV_CHECK --> S_INVALIDATE : hit E/S
    S_INV_CHECK --> S_INV_WB_WAIT : hit M → WriteL2
    S_INVALIDATE --> S_INV_ACK : MESI→I（下一拍）
    S_INV_WB_WAIT --> S_INVALIDATE : 写回完成
    S_INV_ACK --> S_IDLE : 发 Ack

    S_IDLE --> GD_LOOKUP : L2 GetData(addr) 锁存+lookup
    GD_LOOKUP --> GD_RETURN : 命中 owner line
    GD_RETURN --> S_IDLE : 返回整行；M→S（隐含降级）
    GD_LOOKUP --> GD_MISS : miss（未定义，loose end）
```

## 3. L2 一致性概览（概念图，非完整 FSM）

```mermaid
flowchart LR
    subgraph IN[L1 → L2 入站]
        GetS["GetS<br>Load Miss"]
        GetM["GetM<br>Store Miss / Hit-S 升级"]
        WB["WriteL2/WriteBack<br>M victim / Invalidate 时 M"]
    end
    subgraph L2C[L2 控制器]
        CTRL["L2 Controller<br>tag lookup + 目录读"]
        DIR["目录元数据<br>Tag+Valid · Dirty ·<br>Directory State S/E/M ·<br>Owner/Sharer Vector"]
    end
    subgraph OUT[L2 → L1 / 下级]
        Inv["Invalidate"]
        GD["GetData"]
        DR["Data Resp<br>整行 + 权限"]
        MEM["Lower Memory<br>refill（未展开）"]
    end

    GetS --> CTRL
    GetM --> CTRL
    WB --> CTRL
    CTRL --> DIR
    DIR -- "目录 E/M：向 owner 取数据" --> GD
    DIR -- "目录 S：L2 数据可直接响应" --> DR
    DIR -- "收回 sharer 副本" --> Inv
    CTRL -- "L2 miss/invalid" --> MEM
```

## 关键约定（来自文档）

- L1 hit = tag match 且 MESI != I；tag match + MESI==I 仍按 miss。
- L1 的 E→M **静默**（不通知 L2）→ L2 目录看到 E 不能假设自身 Data SRAM 最新，E/M 都可能需向 owner 取数据。
- Write-Allocate + Write-Back；SRAM 同步读（Lookup 发读、下一状态取结果）。
- 当前 L1 控制器按**阻塞式**理解（一个 miss 事务未完成前不接受会破坏它的事务）。

## 未完成项（对应文档 §10，暂不展开）

- L2 GetS/GetM 完整状态转移、memory refill。
- L2 多 sharer Invalidate/Ack 计数、owner data 回收。
- L2 replacement + inclusive eviction。
- GetData 是否拆 FetchShared / FetchInvalidate。
- 并发/非阻塞（MSHR、transaction ID、多 outstanding）未纳入第一版。
