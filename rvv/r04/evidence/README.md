# R04 原始证据索引

最终保留的RTL及全部运行时源码：`5e40737ff65def14294a274ad60f5f3f20923488`。选择提交`59a38cc08f4b5d0b56855b8378201583e0ab72c7`恢复相同Git tree；见 `retained-tree-equivalence.json` 与空 `retained-source-equivalence.txt`。后续报告提交仅增加文档、证据和报告解析工具。

- `cloud/physical`：最终C1/C2/C3及P1–P7；C3完整种子0–999日志，oracle生成SHA另存。
- `alan/physical`：最终S1/S2、全部最差10路径、资源层级、路由状态、BUFG网络和DSP寄存器配置。
- `cloud/closure`、`alan/closure`：进位/DSP B/旁路行粒度候选1ca8487；容量探针7297873只增加测试，RTL相同。
- `cloud/readcmd`、`alan/readcmd`：读命令入口加拍b8634cd；完成全部验证后因面积、裕量、带宽较差撤回。
- `alan/pipeline`、`alan/age`：早期机制阶段的真实资源和时序，包括失败的S2和非时钟BUFGCE。
- `stage-index.json`：各目录的源码、实际主机记录、exit code；exit0不代表性能、预算或时序阈值通过。
- `manifest-sha256.json`：此目录各文件哈希；大RTL、DCP、完整工具日志、Spike ELF/完整dump继续留远端。

实际仿真主机cloud_chen，`/home/cloud_chen/evidence/rvv-r04`；Vivado主机Alan，`/home/chen/FUN/flow-r04-evidence`。cwd、命令、工具、阈值与未闭合项见 `docs/tasks/R04-report.md`。`simulation-host-snapshot-20261007.txt`仅是历史审计快照，每次新执行仍须读取共享实时配置。

C3参考程序与golden全部生成并实际运行Spike于`final/c3`的32c2ac0；最终DUT复用这些不变oracle，先检查基础生成器/插件/link.ld Git diff与R04 random/main AST相同，再检查1000份fixture与独立字节算术。未复用历史DUT通过结论。
