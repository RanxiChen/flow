# 新前端接入BE2后端：接口所有权与删除范围

用户2026-10-12授权：生产顶层改为最近完成的流水前端及BE2-P重构后端，删除旧前端模块及接线路径。工作分支refactor/be2-backend-decoupling，集成起点2746032。历史板上bit及证据保留；源码删除不删除已有交付文件。

## 接线与生命周期

| 接口 | 所有者与规则 |
|---|---|
| F0/F1/F2及instruction队列 | BreezeFrontendPipeline；命中继续流水，指令payload保持到backend ID接收，global/EX错误解析清年轻owner |
| ID/EX/MEM/PERM/WB及结果后台 | 当前BreezeBackend（BE2-P）；ID预留，EX只允许L1D入口不接收导致等待，MEM/PERM无条件推进，WB有界判定，W2/长结果自主保持 |
| 指令预测旁带 | eventPc为末parcel地址，10位historyBefore/phtIndex、RAS push/pop及预测方向/目标随原始指令进入EX，不能用解析当时的前端当前历史替代 |
| EX解析及训练 | ID从4项反馈容量预留；EX解析fire立即处理错误路径和GHR/RAS恢复，训练记录保持到PHT/BTB均可接收；训练队列满只挡新ID，不挡EX或WB |
| 反馈取消 | 全局恢复释放尚未到EX的预留；已经合法接受的训练保留。与旧C2一致，EX同拍WB kill不产生新的解析记录 |
| 提交GHR | 只使用WB确实提交的条件分支及保存的actual taken，不用nextPC与fallthrough是否相等猜实际方向 |
| 全局重启 | 后端给出异常/中断/replay/xret/SATP/FENCE.I/SFENCE及权限CSR写的重启；跨一个寄存边界，与新CSR context及permissionEvent同拍到前端 |
| FENCE.I及SFENCE | 明确restart PC；SFENCE在WB发出原脉冲，前端block维持BE2-P条件排空；不改变MMU内部刷新与PTW drain规则 |
| MMU/iTLB/dTLB/PTW | 每核使用新前端内的一份Sv39Mmu；导出真实dTLB及独立mmuIdle。标量L1D与RVV翻译仲裁器接其dTLB；PTW经实际L1D物理读口，不能旁路到L2 |
| 指令refill | 新VIPT ICache→原L1IClient→共享L2或RVV dataless home；保持已接受miss归属和错误返回、owner probe语义，预取关闭 |
| HPM | F0 accepted query计ICache access，真实demand refill脉冲计miss；L1D及BE2事件继续接原CSR HPM |

正常路径是前端instruction握手进入ID，下一拍到EX，随后MEM/PERM/WB，算术及命中指令可以交叠。多个条件分支使训练端饱和时，已分配反馈记录继续被EX填写，PHT/BTB最终释放记录；后续ID等待注册的可用容量，不给主流水级添加状态机或全局保持。

自行裁定：使用4项注册反馈记录和ID预留来适配新前端Decoupled训练接口，避免把ready接到EX；权限CSR写时增加前端全局restart，避免旧权限查询残留。保留已有后端时序测试的只读观察输出，生产接线使用新resolve/restart/committedBranch，不再使用旧BTB/PHT/GHR脉冲或旧frontend boundary。

新的默认GShare history为冻结前端设计的10位；所有产品共用该配置。配置断言从旧默认8更新为10，属于获授权的新前端配置接入，不改变程序/golden/seed/watchdog。显式baseline配置的功能与新接口宽度仍须验证。

## 删除范围与验证

生产接线删除旧BreezeFrontend、BreezeInstrRealigner、BreezeFetchTranslator、FetchTlbClient、FetchBuffer及BreezeFrontendBoundary，及仅为这些旧模块存在的生成/测试入口。旧L1ICache/BTB/PHT在确认无新路径引用后删除；保留L1IClient、新VIPT cache/new predictor、RVC decoder和共用算术/存储原语。当前唯一后端为重构后的BreezeBackend，不另留旧执行后端。

先执行整核吞吐、后端定向合同与必要ISA/异常/kill/Sv39、多核及RVV访存门禁；不放宽前一轮新增0.5 IPC诊断门槛或已有golden。删除时迁移仍有意义的接口/异常覆盖，不能把测试删除当作使失败消失。三配置的RTL/BIOS、routed timing、bitstream与上板结果分别记录，旧时序不能算作新顶层通过。当前本页仅记录接线方案与进行中的源码修改，验证结果待补。
