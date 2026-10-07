# R04：预取与数据通路流水报告

分支 `feat/rvv-20261005`，起点 `1789ddd3732457c24dfc62b9817065e0bc71fd80`。第 0 步 `fcd4d4d` 已先提交并 push 设计文档与任务书。实现工作区 `/home/chen/leisure/flow-rvv`；未跟踪的 `AGENTS.md` 保留。下列源码 SHA 与报告提交分开绑定，报告提交不自动成为已验证 RTL 的 SHA。

最终保留 RTL 为 **`5e40737ff65def14294a274ad60f5f3f20923488`**；选择提交 **`59a38cc08f4b5d0b56855b8378201583e0ab72c7`** 的完整 Git tree 与5e40737相同（`083bce64a7b2de915a28bb3a5293daff6fe45670`），见 `rvv/r04/evidence/retained-tree-equivalence.json` 与空diff证明。后续交付只增加报告/证据/报告解析工具，不改变已验证RTL或测试。`b8634cd` 的读命令加拍候选也完成全部验证，但面积、带宽和布线裕量均较差，已撤回该尝试并保留证据。

**C1/C2/C3、P4/P5/P6、S1总量/综合WNS/VRF LUTRAM、S2均通过；P1/P2失败，SB/VRF/ALU单项面积超预算，普通/慢速vmacc的DSP内部寄存器未全部满足B3。R04未全部通过。** 交付当前实测候选和结构选项，阈值、golden、断言、时钟和策略均未放宽。

## 执行、版本和复现

仿真、编译、RTL 生成每次启动前重新读取 `/home/chen/leisure/flow/docs/cross-project/simulation-host.md`，按当前配置检查免密 SSH、环境、Java/Verilator/sbt、内存与磁盘后使用 **cloud_chen**。没有因 golden/断言失败换主机。源码 push 完成、远端 fast-forward 与准确 SHA 核对完成后才启动下一项。

| 用途 | 实际 cwd / evidence root |
| --- | --- |
| C1/C2/性能 | `/home/cloud_chen/work/flow-rvv-r04-20261007` / `/home/cloud_chen/evidence/rvv-r04` |
| C3/容量探针 | `/home/cloud_chen/work/flow-rvv-r04-final-20261007` / 同上 |
| S1/S2 | Alan `/home/chen/FUN/flow-r04-evidence`；冻结 cloud C1 输出，Vivado 在各阶段 `s1`/`s2` 目录执行 |

cloud 工程 sbt1.9.7（安装 launcher 路径 `/home/cloud_chen/tools/sbt-1.11.2/bin/sbt`）、Java11.0.32.1、Verilator5.028、GCC13.2；Alan Vivado2022.2 Build3671981，XCKU040 `xcku040-ffva1156-2-e`、OOC、10ns。所有硬件执行 nice10，单任务4核；同主机同时最多8核，不超过半数核。Vivado `general.maxThreads=4`；这是线程上限，未更换综合/实现策略。S2 使用原样 `opt_design`、`place_design`、`route_design`，未添加 false path/multicycle，未改时钟。

云端现成 Spike 导出包缺 `insn_macros.h`，失败留 `a2/c2/build-reference.log`。随后从 Alan 复制干净源码 **`76ce016b6765d66c93522b0ea9a16a44841cb331`** 和仅去调试符号的 Spike 二进制。原二进制 SHA256 `c0a8eb834cc94e92afb372f29bd7d2a87215c5fb6ee0dc19ed84792e64222c2a`；云端实际运行副本 SHA256 **`4e0424afa1234a304d9620dde8b1027be0993c63c0d1038c774d4b429138c01e`**。源码/二进制传输包和哈希保留证据根，未改参考指令语义；dot 插件在云端从仓库源码重编译，并另用独立字节算术检查实际 Spike 快照。

C3 的 1000 个扩展程序由 `32c2ac0` 逐个编译 ELF 并实际执行 Spike，保存在 `final/c3/fixtures`。最终 DUT 复用这些 oracle，`run-random-replay.sh` 先验证原 R02 生成器/插件/link.ld 无差异，以及 R04 `random`、`main` 的 AST 无差异、fixture 数为1000，再检查插件字节算术，最后让真实最终 RTL 运行完整种子0–999、全部 golden dump。复用的是不变 oracle，不是历史 DUT 结果。参考源码 SHA、二进制/插件哈希、oracle SHA、DUT SHA 分别留档。

### 验证命令

以下命令在上表 cwd 执行；每次由本地读共享配置、远端预检、核对 SHA 后启动。完整日志、退出码、主机和 SHA 见相应证据目录。

```bash
# cloud 环境：source /home/cloud_chen/setup/activate-flow.sh
export R04_SBT=$(command -v sbt)
export R02_SPIKE_SRC=/home/cloud_chen/evidence/rvv-r04/spike-source
export R02_SPIKE_BUILD=/home/cloud_chen/evidence/rvv-r04/spike-build
export R02_SPIKE=/home/cloud_chen/evidence/rvv-r04/spike-build/spike
export R04_SPIKE_SOURCE_SHA=76ce016b6765d66c93522b0ea9a16a44841cb331
export R02_CC=/usr/bin/riscv64-unknown-elf-gcc
bash rvv/r02/run-c1.sh /home/cloud_chen/evidence/rvv-r04/physical/c1
bash rvv/r04/run-c2.sh /home/cloud_chen/evidence/rvv-r04/physical/c2
bash rvv/r04/run-random-replay.sh /home/cloud_chen/evidence/rvv-r04/final/c3 /home/cloud_chen/evidence/rvv-r04/physical/c3
bash rvv/r02/run-performance.sh /home/cloud_chen/evidence/rvv-r04/physical/performance
```

S1 输入从同 SHA C1 的 `rtl/512-512` 复制到 Alan `physical/s1/rtl`，复制版本化 `rvv/r04/{synthesize,implement}.tcl`、`clock.xdc`，逐文件 SHA256 核对；S2 使用其 DCP，保存 DCP 与 Tcl 的输入哈希。执行形式如下：

```bash
# cwd=/home/chen/FUN/flow-r04-evidence/physical/s1
sha256sum -c input-sha256.txt
taskset -c 4-7 nice -n 10 /home/chen/Tool/FPGA/Vivado/2022.2/bin/vivado \
  -mode batch -source synthesize.tcl -tclargs rtl reports -log vivado.log -journal vivado.jou
# cwd=同根/physical/s2；默认 opt/place/route
taskset -c 0-3 nice -n 10 /home/chen/Tool/FPGA/Vivado/2022.2/bin/vivado \
  -mode batch -source implement.tcl -tclargs ../s1/reports/rvv-r04-synth.dcp reports \
  -log vivado.log -journal vivado.jou
```

## 实现决定

| 参数 | 默认值 | 理由 |
| --- | ---: | --- |
| 已提交 VIQ | 32 | 提供请求超前窗口；分派与请求两个游标 |
| 未提交暂存 | `min(8,viqDepth)` | 8项足够翻译/判定准备；不随 VIQ 深度线性复制宽移位寄存器 |
| 记分板 | 16 | 沿用 R03，不用增加分派容量代替独立预取 |
| 单元队列 memory/ALU/MAC/FP/cross | 4/2/2/2/2 | 沿用原结构 |
| VLSU 描述符 / 区间表 / 翻译 tag | 16 / 16 / 8 | 区间与访存跟踪上限一致；独立分派绑定 |
| 返回缓冲 / burst / AXI ID | 16384B / 4 beat / 单 ID | 有序返回，按信用预留，R 恒 ready |
| 年龄号 | 8 bit | 活跃差值严格小于半空间，覆盖未提交、两队列、SB、区间、翻译和描述符 |
| ALU / dot 输出预留 | 8 / 16 行 | 读前取得输出信用，固定数据流水不被下游宽保持网停住 |

B1：commit 同时写两个分布式 FIFO，分别服务分派与请求游标；容量预留为未提交数加两队列剩余数的最大值。请求游标消费非访存项，只有已提交的 Ok 访存可进入 VLSU。串行项仍未提交，不能预取。

VLSU 将 live 与 bound 分开。预取时分配描述符和完整年龄号，可发 load AR、占信用、接收 R；分派到原 memory 队列时，完整年龄号唯一匹配描述符并绑定 SB slot。绑定前不能写 VRF。请求中的当前描述符另有 `requestDesc` 寄存器，去除从所有描述符选择到 AR/burst metadata 的长路径。store 绑定前不发 AW、不读 VRF、不失效；最后 W 发完前请求游标不开始下一条访存。区间重叠检查仍按原表，store 直到所有 B 返回后才释放区间。

B2：每个 VRF BRAM 副本读出先寄存，再进行 bank 选择；选 bank 延迟与数据匹配。每个写 bank 的 data、enables、address、valid 在仲裁后寄存，下一拍物理写。writeDone 和 MAC/load 完成观察事件延迟一拍，不能在物理写之前解除冒险。原同址 BRAM 读/写断言保留。

B3/B4：dot 的读地址、BRAM/读出、A/B、M、P、四乘积平衡加法树、累加、输出 FIFO、bank 写入分级。ALU 跨指令固定流水。两者按实际 DLEN row 保存单元内旁路，数据为不复位的异步分布式存储，valid/age 为寄存器。其他单元写回 snoop；普通 vmacc 同属 MAC wrapper，也必须 snoop dot 旁路。外部较老写回不会覆盖更年轻旁路；每次分派推进年龄租约，使超半空间的旧缓存标签失效，避免回绕后重用旧数据。旁路所有权按实际 `vl` 触及的 DLEN 行计算，不能把未触及的 LMUL 尾部或零操作当作已生产数据。原跨单元 RAW/WAR/WAW、权重 RAW 与 masked v0 检查保留。

ALU 部分写需要读取目的旧值，按字节合并旁路副本以保持 tail/mask undisturbed；对应第三个执行读口。动态 SEW 的加减用 byte generate/propagate，进位最多在8字节块内，避免串接多次9位加法形成长 carry 网络。dot 标量 signed/unsigned 扩展放在 B 寄存器前，保持数据与 token 对齐。

B5：固定流水自由推进，由 valid 标记有效结果；读前预留输出空间，输出反压不形成 DSP/ALU 全宽 hold。普通 vmacc 保留单指令 sequencer；慢 SEW64 路径仍逐元素计算，乘积与累加分开，输入/结果保持按64bit分片使用保留的局部使能，删除原 `captureSlow`、`operandsHeld*` 全局保持。Chisel的dontTouch不足以阻止Vivado合并等价使能，最终用 `RvvLocalEnable` 小模块中的 `DONT_TOUCH` 触发器保留副本，每个输出只控制64bit；它仍是数据使能，不作时钟。FP/串行执行框架未扩展，无形式化。

零长度/`vstart>=vl` 访存即使早释放了空区间，仍保留描述符到分派绑定和 SB 完成通知。读完成进度延迟2拍，对齐BRAM读出取数，避免过早解除WAR。其 progress 与实际 store gather/B 退休共享端口时必须仲裁，不能先释放描述符而丢失通知。ALU/dot 的零操作也只在该完成端口可用时退休，且不拥有数据旁路。

### 各单元延迟

单位是从接受某行读地址的时钟沿到该行物理写 VRF 的时钟沿差值，列无冒险/无端口和输出反压的最小延迟；不是整条 LMUL 指令完成延迟。额外停顿继续由原合同决定。

| 单元 | R03 → R04（拍） | R04 位置 / 说明 |
| --- | --- | --- |
| ALU 向量操作 | 1 → 6 | BRAM1，读出2，操作数/旁路与运算3，结果4，FIFO可出5，物理bank写6；稳态每拍一行 |
| dot/dotsu | 1 → 9 | BRAM1，读出2，A/B3，M4，P5，加法树6，累加7，FIFO可出8，物理bank写9；相邻 LMUL4 每4拍一条 |
| vmacc SEW8/16/32 | 1 → 7 | BRAM/读出/操作数后，乘积、累加、局部结果保持、bank输入分级；保留单指令执行 |
| vmacc SEW64 | `DLEN/64+2` → `DLEN/64+6` | 默认512bit为10→14拍；逐元素乘积后单独累加、最后物理写 |
| load | 不适用 VRF 读地址 | 返回缓冲取数/拼接后，write.fire→物理VRF写 0→1拍；AR→写依赖外部延迟与绑定/冒险 |
| store | 无 VRF 写回；读→W最小2→3 | BRAM/读出后 gather，随后 AXI W；非对齐多行/反压增加拍数 |
| 跨lane标量读 | 无 VRF 写回；读→scalar valid 2→3 | 读出寄存后捕获并保持标量结果 |
| FP/串行 | 未实现 → 未实现 | 不给执行性能或延迟证明 |

实际DSP映射见S1/S2的 `dsp-pipeline.tsv`：64个dot DSP全部为 **AREG/BREG/MREG/PREG=1/1/1/1**；80个普通vmacc DSP为1/1/0/0；10个慢64bit DSP在S1为0/0/0/0，S2仅3个吸收PREG，其余7个仍0/0/0/0。因此dot流水已达内部寄存器要求，**B3整个乘加单元只部分闭合**。普通/慢速路径虽已拆开乘积与加法，不能把外部FF分级当作全部DSP内部M/P已经使用。

## 正确性与测试追踪

1. **预取与信用**：信用按程序顺序取得，最老返回 beat 只能等待自己的绑定和冒险解除。更老指令不依赖年轻预取结果；R 始终 ready，信用直到该 beat 所有 piece 写回后才释放。C2 的512B（仅2个4-beat burst）/80拍延迟用例检验前进、容量和全部最终字节。
2. **store 屏障和重叠**：请求发生在 commit 后；store 绑定后才执行 AW/VRF read/invalidate，最后 W 前不越过。原 driver 对每个 AR/AW 检查程序顺序、页切分和更老重叠访问的 R/B 完成。新非对齐 store→overlap load 使用40拍 B 延迟，覆盖等待 B；没有绕过区间表。
3. **WAR**：返回数据可提前，但 unbound 或被更老读覆盖的 load 不能写 VRF。新用例同时记录 WAR 返回与更老 queued-dot 的末读，断言前者早于后者，并比对完整 Spike dump；原独立请求在 WAR 阻塞期间推进的断言保留。
4. **年龄回绕**：272对 load/dot 超过完整年龄号空间，live 年龄窗口、唯一分派绑定断言保留；原 kill 后迟到 translation tag 测试保留。新增长年龄间隔后跨单元写回检验 cache tag 失效。
5. **同单元旁路**：16条依赖 LMUL4 dot 已有操作数在 VRF 后开始，合同仍≤66拍；新加入零dot/ALU和短VL LMUL尾部依赖。C3原随机64项完整保留，每种子追加慢reader、8条依赖dot和更年轻load，真实DUT验证全部1000种子。

### 既有测试时序改动（值与断言不放宽）

| 文件/检查 | 旧 → 新 | 原因 |
| --- | --- | --- |
| `RvvRegisterFileSpec` 写入后 mask/data 采样 | write握手1沿 → 握手1沿+物理写1沿 | bank输入新寄存器；仲裁 ready 的期望保持原拍和值 |
| 同文件同步读采样 | 1沿 → 2沿 | BRAM读出加一拍；原每字节期待值/部分写保留 |
| `RvvR03Spec` BRAM输出保持检查 | 写1沿/读1沿 → 写2沿/读2沿 | 同上，0x1234/0x5678和未消费旧值检查保留 |
| 原 direct scoreboard 测试 | 新 hazard 输入初始化为0 | 保持原单沿 RAW/WAR/WAW 和年龄回绕检查，不进入新旁路 |
| `RvvFrontendSpec` | prefetch.ready初始化/释放 | 新独立消费端口；原 VIQ容量、kill、间隔和判定期望不改 |
| 新 P6 fixture | 初始VRF load drain 后发首dot | 合同规定权重已在VRF；初始标量move造成67拍的失败保留，未增加66阈值 |
| R02 directed 慢reader之后 | 原8条gap保留，追加40条普通ALU gap | 请求预取使原独立load AR早于WAR窗口；补充延长窗口，原WAR/request断言与全部原指令保留 |

200000拍超时、完整dump与scalar期望、原协议/容量/冒险/排序断言均保留。RTL不识别标签；testbench标签只用于新用例的准备屏障和被动测量。

## 链接测量与性能

`macCandidate/macReady/macBlocking` 为被动输出，不进入控制。每拍记录当前 sequencer 首行的其他 RAW、WAR、WAW、执行读口和输出信用；排除的只有被配对的权重源 RAW。`consumerExceptRAWReady` 为首次满足拍，`tReady=max(producerDone,consumerExceptRAWReady)`，链接为实际首读减 tReady。producerDone 已对齐物理写。原224对 `*-pairs.json`、旧 CSV 保留；新 `r04-links-*.csv` 带两时刻，`r04-blocking-*.csv` 保存逐拍原因。旧 JSON `P4Pass<=2` 字段保持历史语义，R04按新口径≤4另判。

最终默认源码5e40737，cloud证据 `physical/performance`，脚本退出0、三组完整Spike状态一致；退出0只表示测量/功能完成。每组896个64B读beat、224个链接配对、223个请求提前配对完整，kernel B和drain时buffer均0B。

| 项目 | 延迟/缓冲 | 峰值占比 | 相对R02(pp) | 相对R03(pp) | 读窗口/kernel拍 | 新P4最大 | 旧P4最大 | P5提前范围 | 判断 |
| --- | --- | ---: | ---: | ---: | --- | ---: | ---: | --- | --- |
| P1 | 1 / 16384B | 70.7741% | -29.2259 | -4.3937 | 1266 / 1401 | 1 | 21 | 14–119 | 失败（≥95%） |
| P2 | 40 / 16384B | 68.6590% | -28.4158 | +37.5263 | 1305 / 1440 | 1 | 21 | 53–119 | 失败（≥90%） |
| P3 | 40 / 2560B | 66.8158% | -22.2498 | +35.6831 | 1341 / 1440 | 1 | 21 | 53–83 | 按要求实测 |

P4（P1/P2各224对）新口径均为：0拍223对、1拍1对，最大1≤4；旧口径均为1拍8对、4拍162对、18拍2对、21拍52对。全部原始CSV和旧pairs JSON在 `rvv/r04/evidence/cloud/physical/performance/results/`，未删排队时间较长的配对。

P6：16条LMUL4依赖dot的读窗口为 **64拍**（首末时刻差63拍，窗口含首末），合同≤66；结果与Spike一致，稳态没有指令间空拍。P7：P2最大14条load超前分派，实际已返回而未消费buffer峰值2048B；P1为14条/4096B，P3为8条/832B。峰值统计是FIFO加当前持有beat，不是已发AR的信用预留。

新增C2大buffer用例：full-queue预取14次、最大超前13条、WAR等待268拍、buffer峰值3840B；WAR首返回第87拍，更老dot末读第240拍。512B/80拍用例：full-queue预取2次、最大超前2条、峰值512B，最终正确drain。

容量探针源码 `72978739308416a6f9049b267fdb9d68cb0aa170`，RTL等价于1ca8487；cloud证据 `closure/capacity`，退出0，完整GEMV Spike dump和224/223配对仍检查。没有增加记分板或MAC队列。

| VIQ / 描述符及区间项 | P2带宽 | kernel拍 | 新P4最大 | P5最小提前 | 最大预取load数 | buffer峰值 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 32 / 16（1ca8487默认候选） | 68.6590% | 1440 | 1 | 53 | 14 | 2048B |
| 64 / 32（探针） | 73.7449% | 1440 | 1 | 53 | 28 | 5632B |
| 128 / 64（探针） | **85.4962%** | 1440 | 1 | 53 | 57 | 13056B |

后两组未做非默认C1/C3/S1/S2全套验收，不宣称默认P2通过或面积/时序符合预算。扩大窗口没有减少kernel周期，64描述符也未到90%。默认保留16项以配合当前已测物理候选；这些数字供后续容量/PPA权衡审阅。

GEMV 每8条 dot 后有跨单元 block-accumulate 与 block-reset。当前事件中196个相邻dot首读间隔为4拍、27个块边界在1ca8487/5e40737为21拍，读命令加拍候选为23拍；不是同单元稳态空拍。跨单元 RAW/WAW 等待物理write，WAR阻止reset过早写入；固定有序分派和原bank组织仍保留。16描述符限制一批256B权重的在途数据约4KiB，即使物理buffer为16KiB，也不能把全部空闲容量自动变成更深请求窗口。容量探针用于区分窗口限制和块边界瓶颈，不放宽P1/P2。

## 面积与时序账

各阶段同默认综合/实现策略；未做S1的中间功能修复提交不填PPA数，不估计每个提交的面积。以下覆盖实际测量的机制阶段。

| 阶段 / SHA | S1 LUT / LUTRAM / FF / DSP / BRAM36+18 | S1 WNS(ns) | S2 LUT / LUTRAM / FF / DSP / BRAM36+18 | S2 WNS(ns) |
| --- | --- | ---: | --- | ---: |
| R03 `148c267` | 46719 / 564 / 15599 / 154 / 199+1 | +0.107 | 47120 / 564 / 15874 / 154 / 199+1 | -3.350 |
| 初版流水/预取 `733aa3a` | 69884 / 958 / 51420 / 154 / 199+1 | +0.986 | 70328 / 958 / 51440 / 154 / 199+1 | -0.266 |
| 容量/缓存/ALU流水 `13c2fc0` | 58398 / 3858 / 23375 / 154 / 199+1 | -0.444 | 58333 / 3609 / 23413 / 154 / 199+1 | -2.697 |
| 局部carry/DSP B/行所有权 `1ca8487` | 57869 / 3858 / 23934 / 154 / 199+1 | +1.593 | 57983 / 3609 / 23957 / 154 / 199+1 | -0.447 |
| 保留物理使能（最终） `5e40737` | 58061 / 3858 / 23926 / 154 / 199+1 | +1.447 | 58251 / 3608 / 23943 / 154 / 199+1 | +0.221 |
| 读命令加拍（撤回） `b8634cd` | 59278 / 3858 / 23933 / 154 / 199+1 | +0.103 | 59577 / 3607 / 23955 / 154 / 199+1 | +0.026 |

按部件列 **LUT/FF/BRAM36+18/DSP**。各列为对应S1的原始层级数；跨层级共享/吸收影响归属，不能把单项变化全部归因于某个独立机制，也不能简单重加替代总量。

| 部件 | R03 148c267 | 初版733aa3a | 容量/缓存13c2fc0 | carry/旁路1ca8487 | 最终5e40737 | 加拍b8634cd | 最终LUT预算 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 前端 | 5545/4158/0+0/0 | 15573/13190/0+0/0 | 5825/4169/0+0/0 | 5855/4170/0+0/0 | 5856/4170/0+0/0 | 5833/4170/0+0/0 | ≤8000；预算内 |
| 记分板 | 4566/1174/0+0/0 | 5782/1184/0+0/0 | 5832/1200/0+0/0 | 6412/1712/0+0/0 | 6421/1712/0+0/0 | 6545/1712/0+0/0 | ≤5000；超1421 |
| VRF | 6508/564/192+0/0 | 9306/2927/192+0/0 | 9473/2919/192+0/0 | 9112/2919/192+0/0 | 9129/2919/192+0/0 | 7862/2972/192+0/0 | ≤7000；超2129 |
| VLSU | 10925/3670/7+1/0 | 12443/6066/7+1/0 | 8894/2364/7+1/0 | 8712/2366/7+1/0 | 8821/2362/7+1/0 | 13012/2389/7+1/0 | ≤16000；预算内 |
| ALU | 9386/1193/0+0/0 | 4783/1731/0+0/0 | 11218/2779/0+0/0 | 11248/2779/0+0/0 | 11245/2779/0+0/0 | 11706/2809/0+0/0 | ≤10000；超1245 |
| MAC | 7922/3733/0+0/154 | 18772/24928/0+0/154 | 15477/8508/0+0/154 | 14688/8552/0+0/154 | 14886/8548/0+0/154 | 12239/8502/0+0/154 | ≤20000；预算内 |
| 跨lane | 31/91/0+0/0 | 52/93/0+0/0 | 48/93/0+0/0 | 51/93/0+0/0 | 51/93/0+0/0 | 47/90/0+0/0 | ≤5000；预算内 |
| 顶层直接/单元队列 | 2530/1016/0+0/0 | 3887/1301/0+0/0 | 2524/1343/0+0/0 | 2664/1343/0+0/0 | 2525/1343/0+0/0 | 2907/1289/0+0/0 | ≤6000；预算内 |

最终VRF LUTRAM=0、192个RAMB36；VLSU 7个RAMB36+1个RAMB18；DSP154，FP尚未实现。SB、VRF、ALU分别超过单项预算，总量58,061≤77,000不替代单项验收。

初版pending随VIQ深度复制，使front15.6k LUT/13.2k FF；独立8项pending降至约5.8k/4.2k。描述符32→16与当前请求寄存器将mem12.4k/6.1k降至约8.8k/2.4k。分布式旁路和FIFO把宽缓存从FF改为LUTRAM，MAC FF24.9k降至8.5k；VRF不使用LUTRAM。ALU流水提高同单元吞吐，但引入三读旁路、输出FIFO和tail保留，最终11.2k LUT仍超10k。行粒度旁路ownership与七客户端progress使SB约6.4k仍超5k。

B6对照：5e40737默认opt/place/route后WNS **+0.221ns**、WHS **+0.050ns**；86,360/86,360条可路由网络全部完成，路由错误0。b8634cd加拍候选为+0.026/+0.052ns，87,470/87,470全路由，错误0。两组BUFG类单元数量均为 **0**（TSV只有cell/type/input_net/driver表头），包括非时钟BUFG/BUFGCE均不存在；完整driver查询原文件保留。选择5e40737，因为总LUT、带宽与布线裕量均优于加拍候选。

保留版本没有VRF读命令入口加拍。加拍候选的ALU/dot/vmacc读到物理写最小延迟分别为7/10/8拍，慢64bit为DLEN/64+7；VRF读测试采样1→3沿，store/cross各多一拍，MAC读事件对齐到真实BRAM读。它完成C1/C2/C3、P6=64、新P4最大2与S1/S2后才撤回，不是通过改期望或撤断言隐藏失败。其GEMV P1/P2/P3为68.0334%/66.1255%/64.2755%，kernel1462/1500/1500拍；全部原始证据见readcmd目录。

### S1 最差10条路径

源码5e40737，原始 `rvv/r04/evidence/alan/physical/s1/reports/worst-paths.rpt`；下表数据延迟=logic+route。

| # | 起点 | 终点 | 级数 | logic(ns) | route(ns) | data(ns) | slack(ns) |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | `mac/dot/reserved_reg[1]/C` | `mem/returnBuffer/ram_ext/Memory_reg_0/ADDRARDADDR[8]` | 22 | 1.803 | 6.221 | 8.024 | +1.447 |
| 2 | `mac/dot/reserved_reg[1]/C` | `mem/returnBuffer/ram_ext/Memory_reg_1/ADDRARDADDR[8]` | 22 | 1.803 | 6.221 | 8.024 | +1.447 |
| 3 | `mac/dot/reserved_reg[1]/C` | `mem/returnBuffer/ram_ext/Memory_reg_2/ADDRARDADDR[8]` | 22 | 1.803 | 6.221 | 8.024 | +1.447 |
| 4 | `mac/dot/reserved_reg[1]/C` | `mem/returnBuffer/ram_ext/Memory_reg_3/ADDRARDADDR[8]` | 22 | 1.803 | 6.221 | 8.024 | +1.447 |
| 5 | `mac/dot/reserved_reg[1]/C` | `mem/returnBuffer/ram_ext/Memory_reg_4/ADDRARDADDR[8]` | 22 | 1.803 | 6.221 | 8.024 | +1.447 |
| 6 | `mac/dot/reserved_reg[1]/C` | `mem/returnBuffer/ram_ext/Memory_reg_5/ADDRARDADDR[8]` | 22 | 1.803 | 6.221 | 8.024 | +1.447 |
| 7 | `mac/dot/reserved_reg[1]/C` | `mem/returnBuffer/ram_ext/Memory_reg_6/ADDRARDADDR[8]` | 22 | 1.803 | 6.221 | 8.024 | +1.447 |
| 8 | `mac/dot/reserved_reg[1]/C` | `mem/returnBuffer/ram_ext/Memory_reg_7/ADDRARDADDR[7]` | 22 | 1.803 | 6.221 | 8.024 | +1.447 |
| 9 | `mac/dot/reserved_reg[1]/C` | `mem/returnBuffer/ram_ext/Memory_reg_0/ADDRARDADDR[12]` | 22 | 1.803 | 6.216 | 8.019 | +1.477 |
| 10 | `mac/dot/reserved_reg[1]/C` | `mem/returnBuffer/ram_ext/Memory_reg_1/ADDRARDADDR[12]` | 22 | 1.803 | 6.216 | 8.019 | +1.477 |

### S2 最差10条路径

源码5e40737，原始 `rvv/r04/evidence/alan/physical/s2/reports/worst-paths.rpt`；下表数据延迟=logic+route。

| # | 起点 | 终点 | 级数 | logic(ns) | route(ns) | data(ns) | slack(ns) |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | `alu/alu/row_reg[2]/C` | `vrf/banks_3_0/memory_reg_4/ENARDEN` | 18 | 2.460 | 6.822 | 9.282 | +0.221 |
| 2 | `alu/alu/row_reg[2]/C` | `vrf/banks_1_1/memory_reg_4/ENARDEN` | 19 | 2.491 | 6.787 | 9.278 | +0.225 |
| 3 | `mac/dot/reserved_reg[3]/C` | `vrf/banks_1_3/memory_reg_0/ENARDEN` | 19 | 2.064 | 7.147 | 9.211 | +0.292 |
| 4 | `alu/alu/row_reg[2]/C` | `vrf/banks_1_2/memory_reg_4/ENARDEN` | 19 | 2.421 | 6.780 | 9.201 | +0.302 |
| 5 | `mac/dot/reserved_reg[3]/C` | `vrf/banks_3_3/memory_reg_0/ENARDEN` | 19 | 2.086 | 7.107 | 9.193 | +0.310 |
| 6 | `mac/dot/reserved_reg[3]/C` | `vrf/banks_3_3/memory_reg_2/ENARDEN` | 19 | 2.086 | 7.094 | 9.180 | +0.323 |
| 7 | `mac/dot/reserved_reg[3]/C` | `vrf/banks_1_3/memory_reg_7/ENARDEN` | 19 | 2.064 | 7.113 | 9.177 | +0.326 |
| 8 | `alu/alu/row_reg[2]/C` | `vrf/banks_3_1/memory_reg_4/ENARDEN` | 19 | 2.408 | 6.760 | 9.168 | +0.335 |
| 9 | `mac/dot/reserved_reg[3]/C` | `vrf/banks_1_3/memory_reg_1/ENARDEN` | 19 | 2.064 | 7.091 | 9.155 | +0.348 |
| 10 | `mem/desc_7_slot_reg[2]/C` | `vrf/banks_3_4/memory_reg_4/ENARDEN` | 13 | 1.494 | 7.652 | 9.146 | +0.357 |

S1/S2 是独立协处理器 OOC 内部时序，未接 Breeze 整核/SoC/板卡。OOC 输入输出未设I/O delay，未指定 HD.CLK_SRC；不宣称挂载路径、整机PPA、FPGA或Linux验证。非默认配置只有要求的C1 RTL/Verilator编译证据。

## 验收总表

| 合同 | 最终状态 | 实际主机 / 证据（根下相对目录） |
| --- | --- | --- |
| C1 | 通过，512/512、256/256、512/256，3/3 | cloud `physical/c1`，exit0 |
| C2 | 通过，frontend8、VRF1、R03机制3、原Spike定向种子0–7、新R04用例3 | cloud `physical/c2`，exit0 |
| C3 | 通过，种子0–999，1000/1000；插件独立算术18675条 | cloud `physical/c3`，exit0；oracle另绑定32c2ac0 |
| P1 / P2 | 失败，70.7741% / 68.6590%，阈值95% / 90% | cloud `physical/performance` |
| P3 | 已测量66.8158%（2560B/40拍） | 同上 |
| P4 | 通过，P1/P2各224对，最大1≤4 | 同上全部CSV |
| P5 | 通过，全部223对；P1提前14–119，P2提前53–119拍 | 同上 |
| P6 | 通过，16条读窗口64≤66，完整Spike结果一致 | cloud `physical/c2/r04.log` |
| P7 | 已测量，P2最大超前14条 / 峰值2048B | cloud性能JSON/CSV |
| S1总量/时序/VRF RAM | 通过，58061≤77000，WNS+1.447，VRF LUTRAM0 | Alan `physical/s1`，exit0 |
| 4.2单项面积 | SB6421>5000，VRF9129>7000，ALU11245>10000；其余预算内 | 同上层级报告 |
| S2 | 通过，WNS+0.221、WHS+0.050，全路由、错误0、非时钟BUFG0 | Alan `physical/s2`，exit0 |
| B3 DSP内部寄存器 | dot64个满足A/B/M/P；普通/慢速vmacc仍部分未满足 | Alan S1/S2 dsp-pipeline.tsv |


## 失败证据、限制与结构选项

初版 `8be6515` Scala编译通过、RTL因新 cross hazard 字段未初始化失败，`1cf6c3c`修复；云Spike缺头文件属于参考工具安装问题。`733aa3a` 的dot负数符号扩展与C2 drain失败已分别修复；首版S2存在非时钟BUFGCE和负WNS，保留原报告。`2e2a65d`性能54.303%/53.333%/51.702%、P6 67拍均按失败保存，后续ALU流水和新用例准备屏障有单独验证。

`cache/c1` 同步与测试启动交叠，SHA记录为6665500而实际编译已到5618e9c，不能作为有效C1验收；目录保留，之后先等待同步准确完成再重跑。首次C3在32c2ac0的seed0完整dump不匹配：dot旁路漏掉同MAC wrapper的普通vmacc写回；4ef07df补齐。67219a5/13c2fc0/df93a69的seed1旁路断言失败进一步揭示未触及LMUL尾部所有权；b99692f修复并1000/1000，1ca8487、5e40737已分别重验1000/1000，b8634cd加拍候选也重验1000/1000后撤回。没有删除断言或改golden让这些失败变绿。

1. **带宽与块边界**：相邻dot稳态已经每4拍一条；每8条dot的跨单元accumulate/reset仍形成21拍首读间隔（读命令加拍候选23拍）。不能把P6通过转成P1/P2通过。仅把VIQ/描述符扩大到128/64，实测P2也只有85.50%，且没有对应PPA证明。可在不改第3节的范围内继续研究描述符/返回metadata的生命周期拆分、紧凑跟踪表、宽字段与单元局部物理实现；该方向仍需完整C/P/S重新验证。
2. **需要结构决定的吞吐选项**：允许跨MAC→ALU→MAC的版本化结果旁路，或把累加器读取/更新/reset的资源拥有与物理VRF写回解耦，使下一块不等待两个跨单元交接。这涉及3.4/3.6的可见性和资源所有权；若改变单元划分/挂载边界，需要先批准新合同。当前未实现、没有改测量程序、没有增加16KiB缓冲来冒充P1/P2通过。
3. **单项面积**：SB有全寄存器mask、年龄判断和行粒度ownership；VRF保留4写bank/6读副本及共享执行口映射；ALU有三读旁路、输出FIFO和部分写旧值。当前SB/VRF/ALU的超量均按真实层级报告保留。可以先裁剪实际未消费字段/端口宽度、缩窄token/FIFO、重排局部组合网络；若减少读副本、改变写bank或并发客户端合同，需要新的3.6决定，不能先减能力再宣布面积达标。
4. **B3剩余实现**：普通vmacc需在不同SEW的乘积选择前明确可吸收的M/P级，64bit慢路径需评估分片乘积和加法树的内部寄存器布局；仍须保持数值、年龄、进度与local-enable一致。当前外部产品/累加分级正确性已通过C2/C3，内部DSP映射不足明确记为未闭合；不因dot的64个DSP通过而给整个单元盖章。

最好完整默认候选为5e40737：S2通过并有+0.221ns裕量、功能全部通过，P4/P5/P6通过，带宽和单项预算仍失败。最好已测P2容量探针为85.50%（不同参数、没有全套PPA验收）。没有等待未授权结构改动，也没有放宽任何验收门槛。

原始RTL、DCP、全Vivado日志、Spike ELF/dump在上述远端证据根；仓库 `rvv/r04/evidence` 保存可审阅的退出/SHA/主机记录、功能日志、全部性能原始CSV、资源/时序/BUFG/DSP报告。未扩FP、串行或形式化范围，未改变越位、区间排序、挂载与单元划分合同。
