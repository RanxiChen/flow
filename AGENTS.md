# 仿真执行主机

每次开始仿真及其编译、RTL 生成前，必须重新读取共享配置：

`/home/chen/leisure/flow/docs/cross-project/simulation-host.md`

按配置先校验 `cloud_chen`，不可用时校验并使用 Alan；两边都不可用则报告具体原因。用户会手动更新该配置中的机器地址和环境路径，不能缓存旧地址或沿用旧的 Alan-only 仿真约束。Vivado 继续使用 Alan。

测试/RTL/golden 失败不等于主机不可用。遵守任务原有的冻结规格、阶段和证据要求，并记录实际执行主机。
