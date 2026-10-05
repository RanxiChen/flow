#!/usr/bin/env python3
"""Regenerate W1 CSVs and their Markdown presentation; no guest execution."""
import collections
import csv
import json
from pathlib import Path
import subprocess
import sys

run, out = (Path(p).resolve() for p in sys.argv[1:])
subprocess.run([sys.executable, str(Path(__file__).with_name("summarize.py")), str(run), str(out)], check=True)
inputs = json.loads((Path(__file__).resolve().parents[1] / "third_party/inputs.json").read_text())
tensors = json.loads((run / "summary/tensors.json").read_text())
data = {}
for name in ("totals", "top20", "kernels", "kernel-vl", "opcodes", "nonmatrix", "memory", "instruction-list", "scalar-ratio"):
    data[name] = list(csv.DictReader((out / (name + ".csv")).open()))
lines = []
def paragraph(s):
    lines.extend([s, ""])
def table(headers, rows):
    lines.append("| " + " | ".join(headers) + " |")
    lines.append("| " + " | ".join("---" for _ in headers) + " |")
    for row in rows:
        lines.append("| " + " | ".join(str(v).replace("|", "\\|") for v in row) + " |")
    lines.append("")
def num(v):
    return f"{float(v):,.3f}"
def pct(v):
    return f"{float(v) * 100:.3f}%"
def key(r):
    return [r[c] for c in ("quant", "mode", "vlen", "phase")]
def rvv(name):
    return [r for r in data[name] if r.get("mode") == "rvv"]

paragraph("# R01 / W1：RVV 工作负载画像")
paragraph("本页是 Alan 上 QEMU user-mode 的动态指令与架构访存统计，依据 [R01 任务书](tasks/R01-rvv-workload-perfmodel.md)。没有周期或 token/s 测量，也没有 W2 模型估计。完整小型表在 `rvv/results/w1/`；原始数据留在 Alan。")
paragraph("## 输入与证据")
paragraph(f"Alan 工作区 `/home/chen/FUN/flow-rvv-r01`，分支 `feat/rvv-20261005`；运行目录 `{run}`（下文简称 RUN）。统计流水线 Flow 提交 `{(run / 'pipeline-source.sha').read_text().strip()}`；runner 构建提交 `8c7a34d`，后续 runner/build 文件没有改变，最终文件一致性见回报中的证据核查。上游 llama.cpp `{inputs['llama_commit']}`，提交日期 `{inputs['llama_date']}`。没有修改上游文件或内核。")
paragraph(f"官方模型仓库 `{inputs['model_repo']}`，固定 revision `{inputs['model_revision']}`。")
table(["模型", "文件字节", "sha256"], [[name, m["size"], f"`{m['sha256']}`"] for name, m in inputs["model_metadata"].items()])
paragraph("提示词见 `rvv/prompt.txt`，实际 32 token；贪心采样，16 个生成 token；线程数和 batch 线程数均为 1。主构建 GCC/G++ 15.1.0，QEMU 11.0.0，RVV ISA `rv64gcv/lp64d`，标量 `rv64gc/lp64d` 且 QEMU `v=false`。BLAS/OpenMP 关闭；RVV 构建关闭 Zfh、Zvfh、Zicbop、Zihintpause，保留上游默认 repack 开关。完整 CMake 命令在 RUN/build.log；选项和复现命令见 [rvv/README.md](../rvv/README.md)。Clang 18.1.3 只作 RVV intrinsics 短片段对照，LLVM 全模型与 Spike/pk 未运行。")
table(["模型", "32-token 提示", "16 个参照 token ID", "RVV 128/256/512/1024 与标量"], [[q, 32, "`" + ",".join(map(str, json.loads((run / f'correctness/{q}-native.json').read_text())["token_ids"])) + "`", "全部一致"] for q in ("q4_0", "q8_0")])
paragraph("正确性原始文件：RUN/correctness/comparison.json 与各 case 的 `.json/.log`；统计运行的 token ID 还会再次比对。QEMU 插件原始文件：RUN/profile/{quant}-{mode}-vlen{N}-functions.csv、-vectors.csv、.json、.log、.exit。case 的全部实际命令见 RUN/pipeline.log，构建/盘点/失败修复记录见 [R01-report.md](tasks/R01-report.md)。全部仿真串行、`nice -n 10`；保留其他 Alan 任务。")
paragraph("### 张量清单")
table(["模型", "张量数/类型", "张量总字节", "token_embd.weight", "output.weight"], [[q, f"{t['tensor_count']} / " + ", ".join(f"{k}:{v}" for k,v in sorted(t["types"].items())), t["tensor_bytes"], next(f"{x['type']}, {x['dimensions']}, {x['bytes']} B" for x in t["token_embd"] if x["name"] == "token_embd.weight"), next(f"{x['type']}, {x['dimensions']}, {x['bytes']} B" for x in t["token_embd"] if x["name"] == "output.weight")] for q,t in tensors.items()])
paragraph("形状按 GGUF 维度顺序记录。逐张量名称、形状、类型、元素数和字节数见 `rvv/results/w1/tensors-q4_0.csv` 与 `tensors-q8_0.csv`。两份模型都有 121 个 F32 张量；Q4_0 文件的输出层实际是 Q8_0，两份模型的嵌入和输出层都分别存储。")
paragraph("## 计数口径")
paragraph("runner 的三个独立 ELF 函数入口切换 prefill/decode/end。prefill 包含 32-token 提示评估和第一次采样；decode 包含随后 15 次单 token 评估与采样。因此 prefill 每 token 除以 32，decode 每 token 除以 15。初始化、加载、tokenization、结束清理和 marker 函数自身不计。统计按照 ELF STT_FUNC 地址范围归属，为函数自身的 exclusive 指令数；共享 helper 不回填给调用者，不能把此数当 inclusive 算子成本。未知归属保留为 `[unknown]`。")
paragraph("插件执行时读取 VL/vtype，记录 SEW 和有符号 `lmul_log2`（0=m1，1=m2，-1=mf2）；vset 和 RVV CSR 指令不处理元素，SEW/VL=0 表示不适用。vector 总数包含 vset，CSR 读写仍属于 scalar 总数，另外列入相关指令清单。向量访存字节来自 QEMU 实际 memory callback，按 unit/strided/indexed/segmented 分类；whole-register 转移归 unit。这些是客户机架构访问量，不是 DDR 流量、cache miss 或持续带宽。")
paragraph("## Q1：指令总数与函数前 20 名")
table(["模型", "模式", "VLEN", "阶段", "阶段指令", "标量", "向量（含 vset）", "每 token 指令"], [key(r) + [r["total"], r["scalar"], r["vector"], num(r["instructions_per_token"])] for r in data["totals"]])
paragraph("所有模型、阶段、VLEN 与标量版的前 20 名及占比完整列于 `rvv/results/w1/top20.csv`。下面展示 RVV 128 的四组；函数名保留 ELF 的 mangled spelling。")
for q in ("q4_0", "q8_0"):
    for phase in ("prefill", "decode"):
        paragraph(f"### {q} / RVV 128 / {phase}")
        table(["排名", "函数", "自身指令", "阶段占比"], [[r["rank"], f"`{r['symbol']}`", r["instructions"], pct(r["fraction"])] for r in data["top20"] if r["quant"] == q and r["mode"] == "rvv" and r["vlen"] == "128" and r["phase"] == phase])
paragraph("## Q2：32 权重块成本和每条指令的 VL")
paragraph("plain dot 用入口 n/32 计块；gemv 还乘输出列 nc。每块比率包括函数自身 setup/epilogue 的摊销。wrapper 与 helper 保留各自的分母，不相加；未进入函数则没有样本。配置指令已经包含在向量/块中。")
table(["模型", "模式", "VLEN", "阶段", "内核", "块数", "标量/块", "向量/块", "vset/块"], [key(r) + [f"`{r['symbol']}`", r["blocks32"], num(r["scalar_per_block"]) if r["blocks32"] != "0" else "不可得", num(r["vector_per_block"]) if r["blocks32"] != "0" else "不可得", num(r["vset_per_block"]) if r["blocks32"] != "0" else "不可得"] for r in data["kernels"]])
paragraph("逐 opcode、SEW、LMUL、VL、动态次数完整表为 `rvv/results/w1/kernel-vl.csv`。下表归并各内核的数据指令 VL，配置/CSR 的 VL=0 不作为元素长度。")
vl = collections.defaultdict(collections.Counter)
for r in data["kernel-vl"]:
    if int(r["vl"]) > 0:
        vl[tuple(key(r) + [r["symbol"]])][int(r["vl"])] += int(r["count"])
table(["模型", "模式", "VLEN", "阶段", "内核", "VL:次数"], [list(k[:4]) + [f"`{k[4]}`", "; ".join(f"{n}:{c}" for n,c in sorted(v.items()))] for k,v in sorted(vl.items())])
paragraph("## Q3：VLEN 与代码路径")
paragraph("固定上游 `ggml/src/ggml-cpu/arch/riscv/quants.c:222–273` 的 Q4_0×Q8_0 dot 每块请求 VL=16（e8,m1），做拆包、扩宽乘加与归约；`:435–479` 的 Q8_0×Q8_0 dot 每块请求 VL=32（e8,m2）。这两个函数没有按 vlenb 扩大每块处理范围。`ggml/src/ggml-cpu/repack.cpp:4996–5005` 的 Q4_0 RVV repack selector 受 `__riscv_zvfh` 条件保护；本次 Zvfh 关闭，不能据文件中存在 256-bit outer-product 内核就声称运行过它。是否实际进入内核见 Q2 和 ELF 前 20 名。")
table(["模型", "阶段", "VLEN", "阶段总指令", "相对 RVV 128"], [[r["quant"], r["phase"], r["vlen"], r["total"], num(int(r["total"]) / int(next(b["total"] for b in rvv("totals") if b["quant"] == r["quant"] and b["phase"] == r["phase"] and b["vlen"] == "128")))] for r in rvv("totals")])
paragraph("## Q4：操作码频度与分类")
paragraph("完整 opcode 次数及阶段占比见 `rvv/results/w1/opcodes.csv`，包括归约、扩宽乘加、移位/拆包、访存、配置及相关 scalar CSR。下面汇总各类别；分母与完整表一致，是向量指令加相关 CSR 指令，CSR 不重复计入总指令。")
categories = collections.defaultdict(collections.Counter)
for r in rvv("opcodes"):
    categories[tuple(key(r))][r["category"]] += int(r["count"])
table(["模型", "模式", "VLEN", "阶段", "类别", "次数", "占比"], [list(k) + [cat, n, pct(n / sum(v.values()))] for k,v in sorted(categories.items()) for cat,n in sorted(v.items())])
paragraph("## Q5：非矩阵运算")
paragraph("完整表 `rvv/results/w1/nonmatrix.csv` 覆盖四种 VLEN 和标量版。下面列 RVV 128；向量数为零说明列中匹配到的符号自身只执行标量指令，不能证明整个算子含其共享 helper 都是标量。attention 的通用矩阵 helper、共享 FP16 转换和 memcpy 等不通过符号范围回溯调用者，无法给出这些算子的完整 inclusive 成本；这是本次不能获得的字段及原因。输出投影计入矩阵 dot，embedding_lookup 是输入查表，二者不混用。")
table(["模型", "阶段", "运算组", "自身指令", "占比", "向量指令"], [[r["quant"], r["phase"], r["group"], r["exclusive_symbol_instructions"], pct(r["fraction"]), r["vector"]] for r in rvv("nonmatrix") if r["vlen"] == "128"])
paragraph("## Q6：向量 load 字节与权重大小")
loads = collections.defaultdict(int)
for r in rvv("memory"):
    if r["kind"] == "load":
        loads[tuple(key(r))] += int(r["bytes"])
table(["模型", "模式", "VLEN", "阶段", "阶段向量 load B", "每 token B", "张量总 B", "每 token/张量总"], [list(k) + [v, num(v / (15 if k[3] == "decode" else 32)), tensors[k[0]]["tensor_bytes"], num(v / (15 if k[3] == "decode" else 32) / tensors[k[0]]["tensor_bytes"])] for k,v in sorted(loads.items())])
paragraph("四种访存类别及 store 字节完整表见 `rvv/results/w1/memory.csv`，零项也保留。不能用向量 load 总量确认严格的“每 token 读一遍整个 GGUF”：GGUF 含元数据；token_embd 是行查表，不遍历其整张量；输出层单独存储且 Q4 文件也使用 Q8；dot 还重复读激活，量化 scale 和其他部分由 scalar load 读取。因此这里给出实际架构字节并对照文件/张量大小，不能据相近或更大就断言 DDR 读完整模型一遍。prefill 的批处理与 decode 独立列出。")
paragraph("## Q7：RVV 及相关 CSR 完整清单")
paragraph("下面是两模型、两阶段、四种 VLEN 的去重并集。逐 case 次数在 Q4 表，逐内核 VL 在 Q2 表；没有进入当前工作负载的指令不在此清单。")
table(["opcode"], [[f"`{r['opcode']}`"] for r in data["instruction-list"]])
paragraph("## Q8：标量 / RVV 总指令数")
table(["模型", "模式", "VLEN", "阶段", "标量指令", "RVV 指令", "标量/RVV"], [key(r) + [r["scalar_instructions"], r["rvv_instructions"], num(r["scalar_over_rvv"])] for r in data["scalar-ratio"]])
paragraph("这是动态指令数量比，不能当性能加速比。QEMU 执行时间受翻译和插件开销影响，没有作为 Breeze 或协处理器速度证据。")
paragraph("## 对设计的含义与限制")
paragraph("实测操作码、短块 VL 和配置频度可以作为下一步快路径与固定开销模型的输入；仅扩大 VLEN 是否减少现有内核指令，需看 Q3 的实际数据。归约、扩宽整数乘加和 Q4 拆包不能遗漏。模型含不同类型的输出/嵌入与 F32 张量，因此不能只建一种量化矩阵模型。架构访存量没有建立 DDR 带宽、延迟或在途深度的实测依据；这里不选择 DLEN、队列深度、自定义指令或 RTL 实现。W2 仍需用户确认。")
paragraph("本次仅覆盖固定提示、16-token 贪心输出、单线程、GCC 15.1.0 和 rv64gcv。SEW/LMUL/VL 是执行时配置，whole-register 操作的实际传输大小以 memory 字节为准；符号计数为 exclusive。没有 full LLVM、Spike 长程序、Breeze 集成、RTL、FPGA、周期、PPA 或 DDR 实测结论。")
paragraph("## 一条命令复现")
paragraph("在 Alan 工作区运行以下命令，即重新生成本页所有动态统计表和 Markdown；逐张量文件由 README 中的 tensors.py 生成。原始执行与两次再生、代表 case 的独立重跑比对见回报和 RUN/pipeline.log。")
paragraph('```bash\npython3 rvv/tools/write-profile.py /home/chen/FUN/flow-r01-runs/20261005-w1 /home/chen/FUN/flow-r01-runs/20261005-w1/presentation\n```')
(out / "rvv-workload-profile.md").write_text("\n".join(lines) + "\n")
print("W1_PRESENTATION_COMPLETE", out)
