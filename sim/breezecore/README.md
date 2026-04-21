# BreezeCore Simulation Workspace

这个目录用于组织 `BreezeCoreSimApp` 相关的仿真测试资产和后续运行入口。

当前先建立最小目录骨架，不改动现有的 `design/breeze_core_memory_map.json`。该 JSON 继续保留在 `design/` 下，作为现有示例输入。

## 目录约定

- `tests/`
  - 放按测试名分组的工作目录。
  - 每个测试目录里自带源文件、链接脚本、生成物目录、结果目录和说明文档。
  - 例如当前的 `tests/branch_test/`。

- `README.md`
  - 说明这个目录的用途和使用约定。

## 与 `design/` 的关系

- `design/` 仍然是 Scala/Chisel 的工程根目录。
- `sbt`、`runMain`、`test` 等命令依然应该从 `design/` 侧调用。
- `sim/breezecore/` 负责组织仿真资产，而不是承载 `sbt` 工程本身。

也就是说，后续更推荐的调用方式是：

1. 在 `sim/breezecore/tests/<test_name>/` 下维护该测试的源码、链接脚本和生成物。
2. 由该测试目录内的 `Makefile` 或脚本进入 `design/` 执行 `sbt "runMain ..."`。

## 后续建议

后面可以在这个目录下继续补：

- 新测试目录
  - 例如 `tests/load_store_test/`、`tests/alu_smoke/`。

- 公共脚本目录
  - 如果后面多个测试共用转换逻辑，可以再抽出 `scripts/`。

## 当前状态

当前目录当前采用按测试分组的骨架：

- 已创建 `tests/`
- 已放入 `tests/branch_test/` 作为第一个样例
- 已创建本说明文件

现有的 `design/breeze_core_memory_map.json` 仍保持不动，继续作为 `design/` 侧原始示例输入。
