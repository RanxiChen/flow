# BreezeClusterAxi 黑箱接口图

- `breeze-top-interface.drawio`：原生可编辑源图。
- `breeze-top-interface.png`：预览；`render.html`：浏览器预览（需联网加载 Draw.io viewer）。
- `source-map.json`：源码版本、散列、接口方向与可选条件。
- `content-check.json`、`render-check.json`：端口覆盖与渲染检查。

这是参数化源码接口视图，展示常规接口及可选 DMA/debug 接口，不代表某一配置全部端口均已生成。N 为核心数；AXI 的通道箭头表示 payload/valid 方向，ready 反向；双箭头表示 Bundle 内含输入和输出。debug 摘列主要字段，retire 与 L1D 事件来自核心 0。硬件内部完全隐藏；未执行 RTL 生成。

图形参考 Harris《Digital Design and Computer Architecture: RISC-V Edition》配套第 4 章第 8 页的模块边界，以及 Cheshire 论文 Fig. 1–2 的总线分组；这些是本图采用的表达惯例，不是声称遵循某项正式绘图标准。
