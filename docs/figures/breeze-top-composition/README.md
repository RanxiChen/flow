# BreezeClusterAxi：模块组成与互连

参数化源码视图，展开顶层的一层子模块实例。包含必选 cluster、按 coherentDma 配置生成的 bridge 和按 debug 配置生成的 monitor。顶层组合与寄存逻辑按功能概括，不是新增子模块。

- `.drawio` 为原生可编辑源，`.png` 为实际渲染预览。
- `source-map.json` 绑定源码 revision、文件散列和连接语义。
- 紫色观察连接只传入监视器；mem/mmio 主功能路径不经过监视器。bridge 仅在 DMA 访问路径上。
- trace、L1D/L2 事件和顶层最近退休记录直接组成 debug 的相应字段，不能误读为 monitor 产生全部 debug。

使用全局参考库 `module-interconnect` 条目；未进行硬件编译、RTL 生成或功能仿真。
