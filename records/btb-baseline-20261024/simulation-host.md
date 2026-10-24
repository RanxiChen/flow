# 仿真主机配置

这份文件是 Breeze、RVV、CISLC-O3 的共享仿真主机配置。用户手动维护下面的地址和环境路径；agent 每次运行前重新读取，不能沿用之前会话记住的地址或主机选择。

## 当前首选：cloud_chen

```text
ssh_target = cloud_chen@47.96.71.231
ssh_port = 22
proxy_url = http://127.0.0.1:18897
breeze_rvv_environment = source /home/cloud_chen/setup/activate-flow.sh
o3_environment = source /home/cloud_chen/setup/activate-o3.sh
workspace_root = /home/cloud_chen/work
evidence_root = /home/cloud_chen/evidence
```

## 备用：Alan

```text
ssh_target = chen@localhost
ssh_port = 2286
ssh_jump = clawbot
proxy_url = http://127.0.0.1:18897
breeze_rvv_environment = source /home/chen/miniforge3/bin/activate flow
o3_environment = source /home/chen/miniforge3/bin/activate cislc-o3
workspace_root = /home/chen/FUN
evidence_root = /home/chen/FUN/flow-runs 或本任务原有的独立 evidence root
```

## 每次执行的规则

1. 仿真前读取本文件，先检查首选主机 `cloud_chen`。使用 `BatchMode=yes`、有限的连接超时，确认免密 SSH、环境激活、任务所需工具版本以及磁盘/内存能够支撑该任务。
2. 需要 GitHub、Maven 等外网下载时，先验证远端反向代理。代理不可用时修复反向转发，不反复直连 GitHub。
3. `cloud_chen` 的连接、环境或任务资源不可用时，改用 Alan 并做同样的检查；两边都不可用则报告具体原因，不在本地偷偷跑仿真。
4. 完成预检后才同步本任务的准确 SHA，使用独立工作区/输出目录执行。记录实际主机、SHA、cwd、命令、工具版本、exit code 和日志路径。
5. RTL 断言、golden 不匹配、测试失败不是主机不可用。保留失败证据，按任务契约定位，不通过换主机或改期望值掩盖失败。
6. Vivado 继续在 Alan 执行；本文件只改变仿真及其编译/RTL 生成的主机选择。已运行的其他任务不自动终止或迁移。

按所选主机上面的字段构造连接预检，变量从本文件当前配置读取，不从历史快照复制（环境检查仍需随后执行）：

```bash
ssh -o BatchMode=yes -o ConnectTimeout=8 -p "$ssh_port" "$ssh_target" true
# 配置了 ssh_jump 的主机再加跳板：
ssh -o BatchMode=yes -o ConnectTimeout=8 -J "$ssh_jump" -p "$ssh_port" "$ssh_target" true
```

环境检查在远端激活对应的 `breeze_rvv_environment` 或 `o3_environment` 后执行，检查任务实际需要的工具。当前云环境还提供 `/home/cloud_chen/setup/check-environment.sh flow|o3` 辅助检查；换机器后以更新后的环境路径为准。

反向转发使用 WSL 本地 HTTP/Mixed 代理 `127.0.0.1:7897`，远端只监听 loopback。转发断开时，在 WSL 的独立终端建立对应隧道并保持运行；创建前检查远端端口没有被现有可用隧道占用：

```bash
ssh -NT -p "$ssh_port" -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
  -R 127.0.0.1:18897:127.0.0.1:7897 "$ssh_target"
# 配置了 ssh_jump 的主机：
ssh -NT -J "$ssh_jump" -p "$ssh_port" -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
  -R 127.0.0.1:18897:127.0.0.1:7897 "$ssh_target"
```

远端使用 `git -c http.proxy=http://127.0.0.1:18897 ...` 或本任务 shell 的代理环境。不要因此改变无关仓库的全局配置。

这份文件只记录当前路由规则。[2026-10-07 环境验收快照](cloud-sim-20261007.md)中的固定地址、SHA 和测试结果是历史证据，不能替代执行前重新读取和预检。
