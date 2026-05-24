# branch_test

这个目录收拢 `branch_test` 相关的文件，避免源文件、链接脚本和后续生成物分散在 `sim/breezecore/` 的不同位置。

## 当前文件

- `branch_test.S`
  - 分支预测相关的裸机测试程序入口。
  - 入口符号是 `_start`。
  - 末尾已经使用 `estop` 机器码结束，适配当前 `BreezeCoreSimApp` 的退出方式。

- `branch_test.ld`
  - 这个测试专用的最小链接脚本。
  - 当前把程序链接到 `0x0`，并以 `_start` 作为入口。
  - 后续生成 `memory.json` 时，脚本默认会读取 ELF entry 作为 `simulation.bootaddr`。

- `Makefile`
  - 把 `.S -> ELF -> dump -> memory.json` 串起来。
  - 后续也可以直接从这里调用 `BreezeCoreSimApp`。

- `gen_case.py`
  - 从链接后的 ELF 中提取可加载段。
  - 自动生成当前 BreezeCore 仿真入口需要的 `memory.json`。
  - 内存布局由链接脚本决定，Python 只负责导出。

- `generated/`
  - 放中间产物。
  - 例如后续生成的 `ELF`、反汇编、二进制导出文件等。

- `out/`
  - 放运行日志、trace、仿真输出等结果文件。

## 当前约定

这个测试目前采用最小 bare-metal 组织方式：

- 不依赖 libc
- 不引入额外 startup 框架
- 直接从 `_start` 开始执行
- 由链接脚本确定符号地址
- 通过 `estop` 正常结束仿真

## 后续建议

当前目录已经具备最小工作流骨架，后续可以直接使用：

```bash
make elf
make dump
make json
make run
```

说明：

- `make elf`
  - 生成 `generated/branch_test.elf`

- `make dump`
  - 生成反汇编文件，便于检查分支和地址展开

- `make json`
  - 生成 `generated/branch_test.json`

- `make run`
  - 调用 `design/` 下的 `BreezeCoreSimApp`
  - 默认使用 `baseline` 核心配置
  - 可以通过 `CORE_PRESET=gshare make run` 切到 gshare 配置

当前支持的核心配置：

- `baseline`
  - 前端不启用 gshare

- `gshare`
  - 前端启用 gshare
  - 当前使用默认 `ghrLength` 和 `btbEntryNum`

如果后面希望把最终稳定的 case 再沉淀到上层公共目录，也可以再复制到 `sim/breezecore/cases/`。
