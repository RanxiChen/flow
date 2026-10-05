# R01 / W1：RVV 工作负载画像

**阶段汇总：用户要求停止后，只纳入已正常退出且 token 匹配的 8/10 个统计 case。W1 尚未完成；缺少 q8_0/rvv/512, q8_0/rvv/1024，独立重跑未运行。停止记录在 RUN/user-stop.txt。没有继续仿真或进入 W2。**

本页是 Alan 上 QEMU user-mode 的动态指令与架构访存统计，依据 [R01 任务书](tasks/R01-rvv-workload-perfmodel.md)。没有周期或 token/s 测量，也没有 W2 模型估计。完整小型表在 `rvv/results/w1/`；原始数据留在 Alan。

## 输入与证据

Alan 工作区 `/home/chen/FUN/flow-rvv-r01`，分支 `feat/rvv-20261005`；运行目录 `/home/chen/FUN/flow-r01-runs/20261005-w1`（下文简称 RUN）。初始统计流水线 Flow 提交 `ef60d1f55558d1578947be805ffac70a17470abc`；并行接续提交 `06f0b362dce028d05787cb28db42c27bc14d0d96`，仅改变调度，插件/runner 没有改变；runner 构建提交 `8c7a34d`，后续 runner/build 文件没有改变，最终文件一致性见回报中的证据核查。上游 llama.cpp `e117148a41d8e9bedb72e4c6c3f003ab0fe7f857`，提交日期 `2026-10-05T19:14:23+05:30`。没有修改上游文件或内核。

官方模型仓库 `Qwen/Qwen2.5-0.5B-Instruct-GGUF`，固定 revision `9217f5db79a29953eb74d5343926648285ec7e67`。

| 模型 | 文件字节 | sha256 |
| --- | --- | --- |
| qwen2.5-0.5b-instruct-q4_0.gguf | 428730208 | `7671c0c304e6ce5a7fc577bcb12aba01e2c155cc2efd29b2213c95b18edaf6ed` |
| qwen2.5-0.5b-instruct-q8_0.gguf | 675710816 | `ca59ca7f13d0e15a8cfa77bd17e65d24f6844b554a7b6c12e07a5f89ff76844e` |

提示词见 `rvv/prompt.txt`，实际 32 token；贪心采样，16 个生成 token；线程数和 batch 线程数均为 1。主构建 GCC/G++ 15.1.0，QEMU 11.0.0，RVV ISA `rv64gcv/lp64d`，标量 `rv64gc/lp64d` 且 QEMU `v=false`。BLAS/OpenMP 关闭；RVV 构建关闭 Zfh、Zvfh、Zicbop、Zihintpause，保留上游默认 repack 开关。完整 CMake 命令在 RUN/build.log；选项和复现命令见 [rvv/README.md](../rvv/README.md)。Clang 18.1.3 只作 RVV intrinsics 短片段对照，LLVM 全模型与 Spike/pk 未运行。

| 模型 | 32-token 提示 | 16 个参照 token ID | RVV 128/256/512/1024 与标量 |
| --- | --- | --- | --- |
| q4_0 | 32 | `2014,3553,264,1372,304,4938,11,264,6366,1156,3880,311,22089,264,3550,369` | 全部一致 |
| q8_0 | 32 | `758,5256,11,7512,279,7354,304,9931,11,323,2968,825,2805,3110,315,32256` | 全部一致 |

正确性原始文件：RUN/correctness/comparison.json 与各 case 的 `.json/.log`；统计运行的 token ID 还会再次比对。QEMU 插件原始文件：RUN/profile/{quant}-{mode}-vlen{N}-functions.csv、-vectors.csv、.json、.log、.exit。case 的全部实际命令见 RUN/pipeline.log 和 RUN/parallel-recovery.log，构建/盘点/失败修复记录见 [R01-report.md](tasks/R01-report.md)。正确性串行；统计至多四个并发单线程进程，全部 `nice -n 10`，小于 Alan 物理核数的一半；保留其他 Alan 任务。

### 张量清单

| 模型 | 张量数/类型 | 张量总字节 | token_embd.weight | output.weight |
| --- | --- | --- | --- | --- |
| q4_0 | 291 / F32:121, Q4_0:169, Q8_0:1 | 422782464 | Q4_0, [896, 151936], 76575744 B | Q8_0, [896, 151936], 144643072 B |
| q8_0 | 291 / F32:121, Q8_0:170 | 669763072 | Q8_0, [896, 151936], 144643072 B | Q8_0, [896, 151936], 144643072 B |

形状按 GGUF 维度顺序记录。逐张量名称、形状、类型、元素数和字节数见 `rvv/results/w1/tensors-q4_0.csv` 与 `tensors-q8_0.csv`。两份模型都有 121 个 F32 张量；Q4_0 文件的输出层实际是 Q8_0，两份模型的嵌入和输出层都分别存储。

## 计数口径

runner 的三个独立 ELF 函数入口切换 prefill/decode/end。prefill 包含 32-token 提示评估和第一次采样；decode 包含随后 15 次单 token 评估与采样。因此 prefill 每 token 除以 32，decode 每 token 除以 15。初始化、加载、tokenization、结束清理和 marker 函数自身不计。统计按照 ELF STT_FUNC 地址范围归属，为函数自身的 exclusive 指令数；共享 helper 不回填给调用者，不能把此数当 inclusive 算子成本。未知归属保留为 `[unknown]`。

插件执行时读取 VL/vtype，记录 SEW 和有符号 `lmul_log2`（0=m1，1=m2，-1=mf2）；vset 和 RVV CSR 指令不处理元素，SEW/VL=0 表示不适用。vector 总数包含 vset，CSR 读写仍属于 scalar 总数，另外列入相关指令清单。向量访存字节来自 QEMU 实际 memory callback，按 unit/strided/indexed/segmented 分类；whole-register 转移归 unit。这些是客户机架构访问量，不是 DDR 流量、cache miss 或持续带宽。

## Q1：指令总数与函数前 20 名

| 模型 | 模式 | VLEN | 阶段 | 阶段指令 | 标量 | 向量（含 vset） | 每 token 指令 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| q4_0 | scalar | 128 | prefill | 68273965275 | 68273965275 | 0 | 2,133,561,414.844 |
| q4_0 | scalar | 128 | decode | 48649647707 | 48649647707 | 0 | 3,243,309,847.133 |
| q4_0 | rvv | 128 | prefill | 11189007509 | 6041902764 | 5147104745 | 349,656,484.656 |
| q4_0 | rvv | 128 | decode | 7198291400 | 4058146665 | 3140144735 | 479,886,093.333 |
| q4_0 | rvv | 256 | prefill | 11023400813 | 6004532808 | 5018868005 | 344,481,275.406 |
| q4_0 | rvv | 256 | decode | 7030287879 | 4021948093 | 3008339786 | 468,685,858.600 |
| q4_0 | rvv | 512 | prefill | 10940646494 | 5985875160 | 4954771334 | 341,895,202.938 |
| q4_0 | rvv | 512 | decode | 6946309164 | 4003861681 | 2942447483 | 463,087,277.600 |
| q4_0 | rvv | 1024 | prefill | 10901359513 | 5977740683 | 4923618830 | 340,667,484.781 |
| q4_0 | rvv | 1024 | decode | 6905816411 | 3995673645 | 2910142766 | 460,387,760.733 |
| q8_0 | scalar | 128 | prefill | 85987895009 | 85987895009 | 0 | 2,687,121,719.031 |
| q8_0 | scalar | 128 | decode | 57258556042 | 57258556042 | 0 | 3,817,237,069.467 |
| q8_0 | rvv | 128 | prefill | 8782468214 | 5706177884 | 3076290330 | 274,452,131.688 |
| q8_0 | rvv | 128 | decode | 6029037629 | 3895026759 | 2134010870 | 401,935,841.933 |
| q8_0 | rvv | 256 | prefill | 8616768230 | 5668793576 | 2947974654 | 269,274,007.188 |
| q8_0 | rvv | 256 | decode | 5860882788 | 3858804907 | 2002077881 | 390,725,519.200 |

已完成模型、阶段、VLEN 与标量版的前 20 名及占比列于 `rvv/results/w1/top20.csv`。下面展示 RVV 128 的四组；函数名保留 ELF 的 mangled spelling。

### q4_0 / RVV 128 / prefill

| 排名 | 函数 | 自身指令 | 阶段占比 |
| --- | --- | --- | --- |
| 1 | `ggml_vec_dot_q4_0_q8_0` | 10477056896 | 93.637% |
| 2 | `ggml_compute_forward_mul_mat` | 208852475 | 1.867% |
| 3 | `_ZL49ggml_compute_forward_flash_attn_ext_f16_one_chunkPK19ggml_compute_paramsP11ggml_tensoriillPfl` | 196181864 | 1.753% |
| 4 | `ggml_vec_dot_f16` | 131814144 | 1.178% |
| 5 | `ggml_vec_dot_q8_0_q8_0` | 99973888 | 0.894% |
| 6 | `ggml_vec_swiglu_f32` | 16593555 | 0.148% |
| 7 | `ggml_compute_forward_rms_norm_mul_fused` | 12905467 | 0.115% |
| 8 | `quantize_row_q8_0` | 10242504 | 0.092% |
| 9 | `ggml_cpu_fp32_to_fp16` | 8073216 | 0.072% |
| 10 | `ggml_compute_forward_add_non_quantized` | 6403188 | 0.057% |
| 11 | `__ieee754_expf` | 6107136 | 0.055% |
| 12 | `_Z29ggml_compute_forward_rope_fltIfEvPK19ggml_compute_paramsP11ggml_tensorb` | 4250736 | 0.038% |
| 13 | `__sincosf` | 1763856 | 0.016% |
| 14 | `llama_sampler_sample` | 1215614 | 0.011% |
| 15 | `_ZNSt6vectorI16llama_token_dataSaIS0_EE17_M_default_appendEm` | 1215567 | 0.011% |
| 16 | `ggml_is_contiguous` | 1176822 | 0.011% |
| 17 | `_ZL26llama_sampler_greedy_applyP13llama_samplerP22llama_token_data_array` | 911634 | 0.008% |
| 18 | `memset` | 769170 | 0.007% |
| 19 | `ggml_row_size` | 520299 | 0.005% |
| 20 | `ggml_backend_sched_split_graph` | 389503 | 0.003% |

### q4_0 / RVV 128 / decode

| 排名 | 函数 | 自身指令 | 阶段占比 |
| --- | --- | --- | --- |
| 1 | `ggml_vec_dot_q4_0_q8_0` | 5091240960 | 70.728% |
| 2 | `ggml_vec_dot_q8_0_q8_0` | 1499608320 | 20.833% |
| 3 | `_ZL49ggml_compute_forward_flash_attn_ext_f16_one_chunkPK19ggml_compute_paramsP11ggml_tensoriillPfl` | 184508130 | 2.563% |
| 4 | `ggml_compute_forward_mul_mat` | 170190360 | 2.364% |
| 5 | `ggml_vec_dot_f16` | 149788800 | 2.081% |
| 6 | `llama_sampler_sample` | 18234210 | 0.253% |
| 7 | `_ZNSt6vectorI16llama_token_dataSaIS0_EE17_M_default_appendEm` | 18233505 | 0.253% |
| 8 | `_ZL26llama_sampler_greedy_applyP13llama_samplerP22llama_token_data_array` | 13674450 | 0.190% |
| 9 | `ggml_vec_swiglu_f32` | 8105400 | 0.113% |
| 10 | `__ieee754_expf` | 7126560 | 0.099% |
| 11 | `ggml_compute_forward_rms_norm_mul_fused` | 6415785 | 0.089% |
| 12 | `quantize_row_q8_0` | 4947300 | 0.069% |
| 13 | `_ZL34ggml_backend_cpu_buffer_get_tensorP19ggml_backend_bufferPK11ggml_tensorPvmm` | 3988380 | 0.055% |
| 14 | `ggml_cpu_fp32_to_fp16` | 3784320 | 0.053% |
| 15 | `ggml_compute_forward_add_non_quantized` | 3269160 | 0.045% |
| 16 | `ggml_is_contiguous` | 3043740 | 0.042% |
| 17 | `_Z29ggml_compute_forward_rope_fltIfEvPK19ggml_compute_paramsP11ggml_tensorb` | 2180160 | 0.030% |
| 18 | `ggml_row_size` | 1491555 | 0.021% |
| 19 | `__sincosf` | 913680 | 0.013% |
| 20 | `ggml_graph_plan` | 650205 | 0.009% |

### q8_0 / RVV 128 / prefill

| 排名 | 函数 | 自身指令 | 阶段占比 |
| --- | --- | --- | --- |
| 1 | `ggml_vec_dot_q8_0_q8_0` | 8170308608 | 93.030% |
| 2 | `ggml_compute_forward_mul_mat` | 208852475 | 2.378% |
| 3 | `_ZL49ggml_compute_forward_flash_attn_ext_f16_one_chunkPK19ggml_compute_paramsP11ggml_tensoriillPfl` | 196369935 | 2.236% |
| 4 | `ggml_vec_dot_f16` | 131814144 | 1.501% |
| 5 | `ggml_vec_swiglu_f32` | 16593555 | 0.189% |
| 6 | `ggml_compute_forward_rms_norm_mul_fused` | 12905467 | 0.147% |
| 7 | `quantize_row_q8_0` | 10242504 | 0.117% |
| 8 | `ggml_cpu_fp32_to_fp16` | 8073216 | 0.092% |
| 9 | `ggml_compute_forward_add_non_quantized` | 6403188 | 0.073% |
| 10 | `__ieee754_expf` | 6107136 | 0.070% |
| 11 | `_Z29ggml_compute_forward_rope_fltIfEvPK19ggml_compute_paramsP11ggml_tensorb` | 4250736 | 0.048% |
| 12 | `__sincosf` | 1763856 | 0.020% |
| 13 | `llama_sampler_sample` | 1215614 | 0.014% |
| 14 | `_ZNSt6vectorI16llama_token_dataSaIS0_EE17_M_default_appendEm` | 1215567 | 0.014% |
| 15 | `ggml_is_contiguous` | 1176822 | 0.013% |
| 16 | `_ZL26llama_sampler_greedy_applyP13llama_samplerP22llama_token_data_array` | 911630 | 0.010% |
| 17 | `memset` | 769170 | 0.009% |
| 18 | `ggml_row_size` | 520299 | 0.006% |
| 19 | `ggml_backend_sched_split_graph` | 389503 | 0.004% |
| 20 | `_ZL34ggml_backend_cpu_buffer_get_tensorP19ggml_backend_bufferPK11ggml_tensorPvmm` | 265892 | 0.003% |

### q8_0 / RVV 128 / decode

| 排名 | 函数 | 自身指令 | 阶段占比 |
| --- | --- | --- | --- |
| 1 | `ggml_vec_dot_q8_0_q8_0` | 5421292800 | 89.920% |
| 2 | `_ZL49ggml_compute_forward_flash_attn_ext_f16_one_chunkPK19ggml_compute_paramsP11ggml_tensoriillPfl` | 184813195 | 3.065% |
| 3 | `ggml_compute_forward_mul_mat` | 170190360 | 2.823% |
| 4 | `ggml_vec_dot_f16` | 149788800 | 2.484% |
| 5 | `llama_sampler_sample` | 18234210 | 0.302% |
| 6 | `_ZNSt6vectorI16llama_token_dataSaIS0_EE17_M_default_appendEm` | 18233505 | 0.302% |
| 7 | `_ZL26llama_sampler_greedy_applyP13llama_samplerP22llama_token_data_array` | 13674462 | 0.227% |
| 8 | `ggml_vec_swiglu_f32` | 8105400 | 0.134% |
| 9 | `__ieee754_expf` | 7126560 | 0.118% |
| 10 | `ggml_compute_forward_rms_norm_mul_fused` | 6415785 | 0.106% |
| 11 | `quantize_row_q8_0` | 4947300 | 0.082% |
| 12 | `_ZL34ggml_backend_cpu_buffer_get_tensorP19ggml_backend_bufferPK11ggml_tensorPvmm` | 3988380 | 0.066% |
| 13 | `ggml_cpu_fp32_to_fp16` | 3784320 | 0.063% |
| 14 | `ggml_compute_forward_add_non_quantized` | 3269160 | 0.054% |
| 15 | `ggml_is_contiguous` | 3043740 | 0.050% |
| 16 | `_Z29ggml_compute_forward_rope_fltIfEvPK19ggml_compute_paramsP11ggml_tensorb` | 2180160 | 0.036% |
| 17 | `ggml_row_size` | 1491555 | 0.025% |
| 18 | `__sincosf` | 913680 | 0.015% |
| 19 | `ggml_graph_plan` | 650205 | 0.011% |
| 20 | `ggml_graph_compute_thread.isra.0` | 545310 | 0.009% |

## Q2：32 权重块成本和每条指令的 VL

plain dot 用入口 n/32 计块；gemv 还乘输出列 nc。每块比率包括函数自身 setup/epilogue 的摊销。wrapper 与 helper 保留各自的分母，不相加；未进入函数则没有样本。配置指令已经包含在向量/块中。

| 模型 | 模式 | VLEN | 阶段 | 内核 | 块数 | 标量/块 | 向量/块 | vset/块 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| q4_0 | rvv | 128 | decode | `ggml_vec_dot_q4_0_q8_0` | 167731200 | 16.299 | 14.054 | 3.027 |
| q4_0 | rvv | 128 | decode | `ggml_vec_dot_q8_0_q8_0` | 63813120 | 15.429 | 8.071 | 3.036 |
| q4_0 | rvv | 128 | prefill | `ggml_vec_dot_q4_0_q8_0` | 345160704 | 16.300 | 14.054 | 3.027 |
| q4_0 | rvv | 128 | prefill | `ggml_vec_dot_q8_0_q8_0` | 4254208 | 15.429 | 8.071 | 3.036 |
| q4_0 | rvv | 256 | decode | `ggml_vec_dot_q4_0_q8_0` | 167731200 | 16.299 | 14.054 | 3.027 |
| q4_0 | rvv | 256 | decode | `ggml_vec_dot_q8_0_q8_0` | 63813120 | 15.429 | 8.071 | 3.036 |
| q4_0 | rvv | 256 | prefill | `ggml_vec_dot_q4_0_q8_0` | 345160704 | 16.300 | 14.054 | 3.027 |
| q4_0 | rvv | 256 | prefill | `ggml_vec_dot_q8_0_q8_0` | 4254208 | 15.429 | 8.071 | 3.036 |
| q4_0 | rvv | 512 | decode | `ggml_vec_dot_q4_0_q8_0` | 167731200 | 16.299 | 14.054 | 3.027 |
| q4_0 | rvv | 512 | decode | `ggml_vec_dot_q8_0_q8_0` | 63813120 | 15.429 | 8.071 | 3.036 |
| q4_0 | rvv | 512 | prefill | `ggml_vec_dot_q4_0_q8_0` | 345160704 | 16.300 | 14.054 | 3.027 |
| q4_0 | rvv | 512 | prefill | `ggml_vec_dot_q8_0_q8_0` | 4254208 | 15.429 | 8.071 | 3.036 |
| q4_0 | rvv | 1024 | decode | `ggml_vec_dot_q4_0_q8_0` | 167731200 | 16.299 | 14.054 | 3.027 |
| q4_0 | rvv | 1024 | decode | `ggml_vec_dot_q8_0_q8_0` | 63813120 | 15.429 | 8.071 | 3.036 |
| q4_0 | rvv | 1024 | prefill | `ggml_vec_dot_q4_0_q8_0` | 345160704 | 16.300 | 14.054 | 3.027 |
| q4_0 | rvv | 1024 | prefill | `ggml_vec_dot_q8_0_q8_0` | 4254208 | 15.429 | 8.071 | 3.036 |
| q4_0 | scalar | 128 | decode | `ggml_vec_dot_q4_0_q8_0` | 167731200 | 0.027 | 0.000 | 0.000 |
| q4_0 | scalar | 128 | decode | `ggml_vec_dot_q4_0_q8_0_generic` | 167731200 | 190.979 | 0.000 | 0.000 |
| q4_0 | scalar | 128 | decode | `ggml_vec_dot_q8_0_q8_0` | 63813120 | 0.036 | 0.000 | 0.000 |
| q4_0 | scalar | 128 | decode | `ggml_vec_dot_q8_0_q8_0_generic` | 63813120 | 242.393 | 0.000 | 0.000 |
| q4_0 | scalar | 128 | prefill | `ggml_vec_dot_q4_0_q8_0` | 345160704 | 0.027 | 0.000 | 0.000 |
| q4_0 | scalar | 128 | prefill | `ggml_vec_dot_q4_0_q8_0_generic` | 345160704 | 190.981 | 0.000 | 0.000 |
| q4_0 | scalar | 128 | prefill | `ggml_vec_dot_q8_0_q8_0` | 4254208 | 0.036 | 0.000 | 0.000 |
| q4_0 | scalar | 128 | prefill | `ggml_vec_dot_q8_0_q8_0_generic` | 4254208 | 242.393 | 0.000 | 0.000 |
| q8_0 | rvv | 128 | decode | `ggml_vec_dot_q8_0_q8_0` | 231544320 | 15.355 | 8.059 | 3.030 |
| q8_0 | rvv | 128 | prefill | `ggml_vec_dot_q8_0_q8_0` | 349414912 | 15.328 | 8.055 | 3.027 |
| q8_0 | rvv | 256 | decode | `ggml_vec_dot_q8_0_q8_0` | 231544320 | 15.355 | 8.059 | 3.030 |
| q8_0 | rvv | 256 | prefill | `ggml_vec_dot_q8_0_q8_0` | 349414912 | 15.328 | 8.055 | 3.027 |
| q8_0 | scalar | 128 | decode | `ggml_vec_dot_q8_0_q8_0` | 231544320 | 0.030 | 0.000 | 0.000 |
| q8_0 | scalar | 128 | decode | `ggml_vec_dot_q8_0_q8_0_generic` | 231544320 | 242.325 | 0.000 | 0.000 |
| q8_0 | scalar | 128 | prefill | `ggml_vec_dot_q8_0_q8_0` | 349414912 | 0.027 | 0.000 | 0.000 |
| q8_0 | scalar | 128 | prefill | `ggml_vec_dot_q8_0_q8_0_generic` | 349414912 | 242.301 | 0.000 | 0.000 |

逐 opcode、SEW、LMUL、VL、动态次数完整表为 `rvv/results/w1/kernel-vl.csv`。下表归并各内核的非配置、非 CSR 指令 VL；其中 vmv.x.s 即使 VL=0 仍可读取第 0 元素，不能把这个 0 当作统计缺失或配置指令的占位值。语义依据：[RISC-V V 1.0 / Integer Scalar Move Instructions](https://docs.riscv.org/reference/isa/unpriv/v-st-ext)。

| 模型 | 模式 | VLEN | 阶段 | 内核 | VL:次数 |
| --- | --- | --- | --- | --- | --- |
| q4_0 | rvv | 1024 | decode | `ggml_vec_dot_q4_0_q8_0` | 0:167731200; 16:1681873920 |
| q4_0 | rvv | 1024 | decode | `ggml_vec_dot_q8_0_q8_0` | 0:63813120; 32:257531520 |
| q4_0 | rvv | 1024 | prefill | `ggml_vec_dot_q4_0_q8_0` | 0:345160704; 16:3461009792 |
| q4_0 | rvv | 1024 | prefill | `ggml_vec_dot_q8_0_q8_0` | 0:4254208; 32:17168768 |
| q4_0 | rvv | 128 | decode | `ggml_vec_dot_q4_0_q8_0` | 0:167731200; 4:4561920; 16:1677312000 |
| q4_0 | rvv | 128 | decode | `ggml_vec_dot_q8_0_q8_0` | 0:63813120; 4:2279040; 32:255252480 |
| q4_0 | rvv | 128 | prefill | `ggml_vec_dot_q4_0_q8_0` | 0:345160704; 4:9402752; 16:3451607040 |
| q4_0 | rvv | 128 | prefill | `ggml_vec_dot_q8_0_q8_0` | 0:4254208; 4:151936; 32:17016832 |
| q4_0 | rvv | 256 | decode | `ggml_vec_dot_q4_0_q8_0` | 0:167731200; 8:4561920; 16:1677312000 |
| q4_0 | rvv | 256 | decode | `ggml_vec_dot_q8_0_q8_0` | 0:63813120; 8:2279040; 32:255252480 |
| q4_0 | rvv | 256 | prefill | `ggml_vec_dot_q4_0_q8_0` | 0:345160704; 8:9402752; 16:3451607040 |
| q4_0 | rvv | 256 | prefill | `ggml_vec_dot_q8_0_q8_0` | 0:4254208; 8:151936; 32:17016832 |
| q4_0 | rvv | 512 | decode | `ggml_vec_dot_q4_0_q8_0` | 0:167731200; 16:1681873920 |
| q4_0 | rvv | 512 | decode | `ggml_vec_dot_q8_0_q8_0` | 0:63813120; 16:2279040; 32:255252480 |
| q4_0 | rvv | 512 | prefill | `ggml_vec_dot_q4_0_q8_0` | 0:345160704; 16:3461009792 |
| q4_0 | rvv | 512 | prefill | `ggml_vec_dot_q8_0_q8_0` | 0:4254208; 16:151936; 32:17016832 |
| q8_0 | rvv | 128 | decode | `ggml_vec_dot_q8_0_q8_0` | 0:231544320; 4:6840960; 32:926177280 |
| q8_0 | rvv | 128 | prefill | `ggml_vec_dot_q8_0_q8_0` | 0:349414912; 4:9554688; 32:1397659648 |
| q8_0 | rvv | 256 | decode | `ggml_vec_dot_q8_0_q8_0` | 0:231544320; 8:6840960; 32:926177280 |
| q8_0 | rvv | 256 | prefill | `ggml_vec_dot_q8_0_q8_0` | 0:349414912; 8:9554688; 32:1397659648 |

## Q3：VLEN 与代码路径

固定上游 `ggml/src/ggml-cpu/arch/riscv/quants.c:222–273` 的 Q4_0×Q8_0 dot 每块请求 VL=16（e8,m1），做拆包、扩宽乘加与归约；`:435–479` 的 Q8_0×Q8_0 dot 每块请求 VL=32（e8,m2）。这两个函数没有按 vlenb 扩大每块处理范围。`ggml/src/ggml-cpu/repack.cpp:4996–5005` 的 Q4_0 RVV repack selector 受 `__riscv_zvfh` 条件保护；本次 Zvfh 关闭，不能据文件中存在 256-bit outer-product 内核就声称运行过它。是否实际进入内核见 Q2 和 ELF 前 20 名。

其他路径不同：`ggml/src/ggml-cpu/vec.cpp:441–448` 的 SwiGLU 以 `vsetvl_e32m2(n-i)` 分批处理，更大的 VLEN 可以减少循环次数；FP16 dot 在同文件 `:338–343` 进入无 Zvfh 的 C 转换/累加 fallback，实际编译产物是否包含向量指令以 ELF 统计为准。因此应同时看整个阶段的 Q3 总数变化和 Q2 量化内核本身的计数，不能把两者混为同一种 VLEN 收益。

| 模型 | 阶段 | VLEN | 阶段总指令 | 相对 RVV 128 |
| --- | --- | --- | --- | --- |
| q4_0 | prefill | 128 | 11189007509 | 1.000 |
| q4_0 | decode | 128 | 7198291400 | 1.000 |
| q4_0 | prefill | 256 | 11023400813 | 0.985 |
| q4_0 | decode | 256 | 7030287879 | 0.977 |
| q4_0 | prefill | 512 | 10940646494 | 0.978 |
| q4_0 | decode | 512 | 6946309164 | 0.965 |
| q4_0 | prefill | 1024 | 10901359513 | 0.974 |
| q4_0 | decode | 1024 | 6905816411 | 0.959 |
| q8_0 | prefill | 128 | 8782468214 | 1.000 |
| q8_0 | decode | 128 | 6029037629 | 1.000 |
| q8_0 | prefill | 256 | 8616768230 | 0.981 |
| q8_0 | decode | 256 | 5860882788 | 0.972 |

## Q4：操作码频度与分类

完整 opcode 次数及阶段占比见 `rvv/results/w1/opcodes.csv`，包括归约、扩宽乘加、移位/拆包、访存、配置及相关 scalar CSR。下面汇总各类别；分母与完整表一致，是向量指令加相关 CSR 指令，CSR 不重复计入总指令。

| 模型 | 模式 | VLEN | 阶段 | 类别 | 次数 | 占比 |
| --- | --- | --- | --- | --- | --- | --- |
| q4_0 | rvv | 1024 | decode | configuration | 710539952 | 24.416% |
| q4_0 | rvv | 1024 | decode | memory | 637781504 | 21.916% |
| q4_0 | rvv | 1024 | decode | other | 587601714 | 20.191% |
| q4_0 | rvv | 1024 | decode | reduction | 232509631 | 7.990% |
| q4_0 | rvv | 1024 | decode | rvv_csr_scalar_instruction | 12081 | 0.000% |
| q4_0 | rvv | 1024 | decode | shift_unpack_bitwise | 342434445 | 11.767% |
| q4_0 | rvv | 1024 | decode | widening_multiply_accumulate | 399275520 | 13.720% |
| q4_0 | rvv | 1024 | prefill | configuration | 1066930550 | 21.670% |
| q4_0 | rvv | 1024 | prefill | memory | 1051197539 | 21.350% |
| q4_0 | rvv | 1024 | prefill | other | 1063457636 | 21.599% |
| q4_0 | rvv | 1024 | prefill | reduction | 350450313 | 7.118% |
| q4_0 | rvv | 1024 | prefill | rvv_csr_scalar_instruction | 12623 | 0.000% |
| q4_0 | rvv | 1024 | prefill | shift_unpack_bitwise | 697007176 | 14.156% |
| q4_0 | rvv | 1024 | prefill | widening_multiply_accumulate | 694575616 | 14.107% |
| q4_0 | rvv | 128 | decode | configuration | 763818175 | 24.324% |
| q4_0 | rvv | 128 | decode | memory | 681117030 | 21.691% |
| q4_0 | rvv | 128 | decode | other | 667085874 | 21.244% |
| q4_0 | rvv | 128 | decode | reduction | 238442551 | 7.593% |
| q4_0 | rvv | 128 | decode | rvv_csr_scalar_instruction | 12081 | 0.000% |
| q4_0 | rvv | 128 | decode | shift_unpack_bitwise | 390405585 | 12.433% |
| q4_0 | rvv | 128 | decode | widening_multiply_accumulate | 399275520 | 12.715% |
| q4_0 | rvv | 128 | prefill | configuration | 1117844500 | 21.718% |
| q4_0 | rvv | 128 | prefill | memory | 1093220001 | 21.239% |
| q4_0 | rvv | 128 | prefill | other | 1143375215 | 22.214% |
| q4_0 | rvv | 128 | prefill | reduction | 356008089 | 6.917% |
| q4_0 | rvv | 128 | prefill | rvv_csr_scalar_instruction | 12623 | 0.000% |
| q4_0 | rvv | 128 | prefill | shift_unpack_bitwise | 742081324 | 14.417% |
| q4_0 | rvv | 128 | prefill | widening_multiply_accumulate | 694575616 | 13.494% |
| q4_0 | rvv | 256 | decode | configuration | 733249112 | 24.374% |
| q4_0 | rvv | 256 | decode | memory | 656105144 | 21.809% |
| q4_0 | rvv | 256 | decode | other | 621664194 | 20.665% |
| q4_0 | rvv | 256 | decode | reduction | 235052311 | 7.813% |
| q4_0 | rvv | 256 | decode | rvv_csr_scalar_instruction | 12081 | 0.000% |
| q4_0 | rvv | 256 | decode | shift_unpack_bitwise | 362993505 | 12.066% |
| q4_0 | rvv | 256 | decode | widening_multiply_accumulate | 399275520 | 13.272% |
| q4_0 | rvv | 256 | prefill | configuration | 1088575516 | 21.690% |
| q4_0 | rvv | 256 | prefill | memory | 1068856569 | 21.297% |
| q4_0 | rvv | 256 | prefill | other | 1097703419 | 21.871% |
| q4_0 | rvv | 256 | prefill | reduction | 352832217 | 7.030% |
| q4_0 | rvv | 256 | prefill | rvv_csr_scalar_instruction | 12623 | 0.000% |
| q4_0 | rvv | 256 | prefill | shift_unpack_bitwise | 716324668 | 14.273% |
| q4_0 | rvv | 256 | prefill | widening_multiply_accumulate | 694575616 | 13.839% |
| q4_0 | rvv | 512 | decode | configuration | 717967131 | 24.400% |
| q4_0 | rvv | 512 | decode | memory | 643604302 | 21.873% |
| q4_0 | rvv | 512 | decode | other | 598955874 | 20.356% |
| q4_0 | rvv | 512 | decode | reduction | 233357191 | 7.931% |
| q4_0 | rvv | 512 | decode | rvv_csr_scalar_instruction | 12081 | 0.000% |
| q4_0 | rvv | 512 | decode | shift_unpack_bitwise | 349287465 | 11.871% |
| q4_0 | rvv | 512 | decode | widening_multiply_accumulate | 399275520 | 13.569% |
| q4_0 | rvv | 512 | prefill | configuration | 1073946465 | 21.675% |
| q4_0 | rvv | 512 | prefill | memory | 1056685735 | 21.327% |
| q4_0 | rvv | 512 | prefill | other | 1074872897 | 21.694% |
| q4_0 | rvv | 512 | prefill | reduction | 351244281 | 7.089% |
| q4_0 | rvv | 512 | prefill | rvv_csr_scalar_instruction | 12623 | 0.000% |
| q4_0 | rvv | 512 | prefill | shift_unpack_bitwise | 703446340 | 14.197% |
| q4_0 | rvv | 512 | prefill | widening_multiply_accumulate | 694575616 | 14.018% |
| q8_0 | rvv | 128 | decode | configuration | 763873820 | 35.795% |
| q8_0 | rvv | 128 | decode | memory | 513410790 | 24.058% |
| q8_0 | rvv | 128 | decode | other | 331714204 | 15.544% |
| q8_0 | rvv | 128 | decode | reduction | 238442551 | 11.173% |
| q8_0 | rvv | 128 | decode | rvv_csr_scalar_instruction | 12081 | 0.001% |
| q8_0 | rvv | 128 | decode | shift_unpack_bitwise | 55025185 | 2.578% |
| q8_0 | rvv | 128 | decode | widening_multiply_accumulate | 231544320 | 10.850% |
| q8_0 | rvv | 128 | prefill | configuration | 1117880079 | 36.338% |
| q8_0 | rvv | 128 | prefill | memory | 748077233 | 24.317% |
| q8_0 | rvv | 128 | prefill | other | 453104645 | 14.729% |
| q8_0 | rvv | 128 | prefill | reduction | 356008089 | 11.573% |
| q8_0 | rvv | 128 | prefill | rvv_csr_scalar_instruction | 12623 | 0.000% |
| q8_0 | rvv | 128 | prefill | shift_unpack_bitwise | 51805372 | 1.684% |
| q8_0 | rvv | 128 | prefill | widening_multiply_accumulate | 349414912 | 11.358% |
| q8_0 | rvv | 256 | decode | configuration | 733277597 | 36.626% |
| q8_0 | rvv | 256 | decode | memory | 488387264 | 24.394% |
| q8_0 | rvv | 256 | decode | other | 286245964 | 14.297% |
| q8_0 | rvv | 256 | decode | reduction | 235052311 | 11.740% |
| q8_0 | rvv | 256 | decode | rvv_csr_scalar_instruction | 12081 | 0.001% |
| q8_0 | rvv | 256 | decode | shift_unpack_bitwise | 27570425 | 1.377% |
| q8_0 | rvv | 256 | decode | widening_multiply_accumulate | 231544320 | 11.565% |
| q8_0 | rvv | 256 | prefill | configuration | 1088594351 | 36.927% |
| q8_0 | rvv | 256 | prefill | memory | 723706625 | 24.549% |
| q8_0 | rvv | 256 | prefill | other | 407404145 | 13.820% |
| q8_0 | rvv | 256 | prefill | reduction | 352832217 | 11.969% |
| q8_0 | rvv | 256 | prefill | rvv_csr_scalar_instruction | 12623 | 0.000% |
| q8_0 | rvv | 256 | prefill | shift_unpack_bitwise | 26022404 | 0.883% |
| q8_0 | rvv | 256 | prefill | widening_multiply_accumulate | 349414912 | 11.853% |

## Q5：非矩阵运算

已完成 case 的完整表为 `rvv/results/w1/nonmatrix.csv`。下面列 RVV 128；向量数为零说明列中匹配到的符号自身只执行标量指令，不能证明整个算子含其共享 helper 都是标量。SiLU/Swiglu 包含图融合后的 SwiGLU 符号自身成本，不单独拆分其中的 SiLU 与门控乘法。attention 的通用矩阵 helper、共享 FP16 转换和 memcpy 等不通过符号范围回溯调用者，无法给出这些算子的完整 inclusive 成本；这是本次不能获得的字段及原因。输出投影计入矩阵 dot；embedding_lookup 按 get_rows 符号计，不能进一步区分输入嵌入查表和其他行抽取，二者不混用。

| 模型 | 阶段 | 运算组 | 自身指令 | 占比 | 向量指令 |
| --- | --- | --- | --- | --- | --- |
| q4_0 | prefill | RMSNorm | 12906545 | 0.115% | 8096550 |
| q4_0 | prefill | RoPE | 4262256 | 0.038% | 984624 |
| q4_0 | prefill | softmax/attention | 196188296 | 1.753% | 118303560 |
| q4_0 | prefill | SiLU/Swiglu | 16593627 | 0.148% | 12994784 |
| q4_0 | prefill | activation_quantization | 10242504 | 0.092% | 2643308 |
| q4_0 | prefill | sampling/argmax | 2127256 | 0.019% | 3 |
| q4_0 | prefill | embedding_lookup | 5411 | 0.000% | 1344 |
| q4_0 | decode | RMSNorm | 6416863 | 0.089% | 3953712 |
| q4_0 | decode | RoPE | 2245440 | 0.031% | 474480 |
| q4_0 | decode | softmax/attention | 184571898 | 2.564% | 128162862 |
| q4_0 | decode | SiLU/Swiglu | 8105472 | 0.113% | 6347520 |
| q4_0 | decode | activation_quantization | 4947300 | 0.069% | 1276890 |
| q4_0 | decode | sampling/argmax | 31908664 | 0.443% | 45 |
| q4_0 | decode | embedding_lookup | 59860 | 0.001% | 20160 |
| q8_0 | prefill | RMSNorm | 12906545 | 0.147% | 8096550 |
| q8_0 | prefill | RoPE | 4262256 | 0.049% | 984624 |
| q8_0 | prefill | softmax/attention | 196376367 | 2.236% | 118462329 |
| q8_0 | prefill | SiLU/Swiglu | 16593627 | 0.189% | 12994784 |
| q8_0 | prefill | activation_quantization | 10322024 | 0.118% | 2692588 |
| q8_0 | prefill | sampling/argmax | 2127252 | 0.024% | 3 |
| q8_0 | prefill | embedding_lookup | 5411 | 0.000% | 1344 |
| q8_0 | decode | RMSNorm | 6416863 | 0.106% | 3953712 |
| q8_0 | decode | RoPE | 2245440 | 0.037% | 474480 |
| q8_0 | decode | softmax/attention | 184876963 | 3.066% | 128420397 |
| q8_0 | decode | SiLU/Swiglu | 8105472 | 0.134% | 6347520 |
| q8_0 | decode | activation_quantization | 4984575 | 0.083% | 1299990 |
| q8_0 | decode | sampling/argmax | 31908676 | 0.529% | 45 |
| q8_0 | decode | embedding_lookup | 59860 | 0.001% | 20160 |

## Q6：向量 load 字节与权重大小

| 模型 | 模式 | VLEN | 阶段 | 阶段向量 load B | 每 token B | 张量总 B | 每 token/张量总 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| q4_0 | rvv | 1024 | decode | 12539417110 | 835,961,140.667 | 422782464 | 1.977 |
| q4_0 | rvv | 1024 | prefill | 17267706718 | 539,615,834.938 | 422782464 | 1.276 |
| q4_0 | rvv | 128 | decode | 12539007214 | 835,933,814.267 | 422782464 | 1.977 |
| q4_0 | rvv | 128 | prefill | 17267688910 | 539,615,278.438 | 422782464 | 1.276 |
| q4_0 | rvv | 256 | decode | 12539066110 | 835,937,740.667 | 422782464 | 1.977 |
| q4_0 | rvv | 256 | prefill | 17267691454 | 539,615,357.938 | 422782464 | 1.276 |
| q4_0 | rvv | 512 | decode | 12539183902 | 835,945,593.467 | 422782464 | 1.977 |
| q4_0 | rvv | 512 | prefill | 17267696542 | 539,615,516.938 | 422782464 | 1.276 |
| q8_0 | rvv | 128 | decode | 15222899374 | 1,014,859,958.267 | 669763072 | 1.515 |
| q8_0 | rvv | 128 | prefill | 22790389326 | 712,199,666.438 | 669763072 | 1.063 |
| q8_0 | rvv | 256 | decode | 15222958270 | 1,014,863,884.667 | 669763072 | 1.515 |
| q8_0 | rvv | 256 | prefill | 22790391870 | 712,199,745.938 | 669763072 | 1.063 |

四种访存类别及 store 字节完整表见 `rvv/results/w1/memory.csv`，零项也保留。不能用向量 load 总量确认严格的“每 token 读一遍整个 GGUF”：GGUF 含元数据；token_embd 是行查表，不遍历其整张量；输出层单独存储且 Q4 文件也使用 Q8；dot 还重复读激活，量化 scale 和其他部分由 scalar load 读取。因此这里给出实际架构字节并对照文件/张量大小，不能据相近或更大就断言 DDR 读完整模型一遍。prefill 的批处理与 decode 独立列出。

补充核查一次解码对活跃量化矩阵的逻辑遍历量：取 Q2 实际 dot 入口块数，Q4_0 每 32 权重块 18 B、Q8_0 每块 34 B（固定上游 ggml/src/ggml-common.h 的 block_q4_0/block_q8_0），与逐张量清单排除 token_embd 后的量化矩阵 payload 比较。这是统计派生的格式字节数，包含 scale，区别于上表实际向量 load。

| 模型 | VLEN | 非嵌入量化张量 B | dot 块数派生 B/token | 比值 | 核查 |
| --- | --- | --- | --- | --- | --- |
| q4_0 | 128 | 345920512 | 345,920,512.000 | 1.000 | 相等 |
| q4_0 | 256 | 345920512 | 345,920,512.000 | 1.000 | 相等 |
| q4_0 | 512 | 345920512 | 345,920,512.000 | 1.000 | 相等 |
| q4_0 | 1024 | 345920512 | 345,920,512.000 | 1.000 | 相等 |
| q8_0 | 128 | 524833792 | 524,833,792.000 | 1.000 | 相等 |
| q8_0 | 256 | 524833792 | 524,833,792.000 | 1.000 | 相等 |

若上表相等，则支持当前 dot 路径每次解码逻辑遍历一次这些活跃量化矩阵；不包括整张 token_embd、F32 张量、KV 和激活的流量，也不意味着每字节都由 DDR 重新读取。没有地址级 cache/DDR 统计，不能给出物理权重读取次数。

## Q7：RVV 及相关 CSR 完整清单

下面是已完成统计 case 的去重并集。逐 case 次数在 Q4 表，逐内核 VL 在 Q2 表；没有进入当前工作负载的指令不在此清单。未完成 case 不能据此排除其他指令。

| opcode |
| --- |
| `csrrs.vl` |
| `csrrs.vlenb` |
| `vadd.vi` |
| `vadd.vv` |
| `vadd.vx` |
| `vand.vi` |
| `vand.vv` |
| `vcpop.m` |
| `vfadd.vf` |
| `vfadd.vv` |
| `vfcvt.f.x.v` |
| `vfdiv.vv` |
| `vfirst.m` |
| `vfmacc.vf` |
| `vfmacc.vv` |
| `vfmadd.vv` |
| `vfmsac.vv` |
| `vfmul.vf` |
| `vfmul.vv` |
| `vfmv.f.s` |
| `vfmv.s.f` |
| `vfmv.v.f` |
| `vfncvt.x.f.w` |
| `vfnmsac.vf` |
| `vfredmax.vs` |
| `vfsgnjn.vv` |
| `vfsgnjx.vv` |
| `vfsub.vv` |
| `vfwcvt.f.x.v` |
| `vfwredosum.vs` |
| `vid.v` |
| `vle16.v` |
| `vle32.v` |
| `vle64.v` |
| `vle8.v` |
| `vle8ff.v` |
| `vlse32.v` |
| `vluxei64.v` |
| `vmadd.vv` |
| `vmaxu.vv` |
| `vmerge.vvm` |
| `vmfgt.vf` |
| `vmor.mm` |
| `vmseq.vi` |
| `vmsleu.vv` |
| `vmsne.vi` |
| `vmsne.vv` |
| `vmul.vv` |
| `vmul.vx` |
| `vmv.s.x` |
| `vmv.v.i` |
| `vmv.v.x` |
| `vmv.x.s` |
| `vmv1r.v` |
| `vmv2r.v` |
| `vnsrl.wi` |
| `vor.vv` |
| `vredor.vs` |
| `vredsum.vs` |
| `vse16.v` |
| `vse32.v` |
| `vse64.v` |
| `vse8.v` |
| `vsetivli` |
| `vsetvli` |
| `vsext.vf2` |
| `vsext.vf4` |
| `vslide1down.vx` |
| `vslidedown.vi` |
| `vsll.vi` |
| `vsrl.vi` |
| `vwmacc.vv` |
| `vwmul.vv` |
| `vwredsum.vs` |
| `vzext.vf4` |

## Q8：标量 / RVV 总指令数

| 模型 | 模式 | VLEN | 阶段 | 标量指令 | RVV 指令 | 标量/RVV |
| --- | --- | --- | --- | --- | --- | --- |
| q4_0 | rvv | 128 | prefill | 68273965275 | 11189007509 | 6.102 |
| q4_0 | rvv | 128 | decode | 48649647707 | 7198291400 | 6.758 |
| q4_0 | rvv | 256 | prefill | 68273965275 | 11023400813 | 6.194 |
| q4_0 | rvv | 256 | decode | 48649647707 | 7030287879 | 6.920 |
| q4_0 | rvv | 512 | prefill | 68273965275 | 10940646494 | 6.240 |
| q4_0 | rvv | 512 | decode | 48649647707 | 6946309164 | 7.004 |
| q4_0 | rvv | 1024 | prefill | 68273965275 | 10901359513 | 6.263 |
| q4_0 | rvv | 1024 | decode | 48649647707 | 6905816411 | 7.045 |
| q8_0 | rvv | 128 | prefill | 85987895009 | 8782468214 | 9.791 |
| q8_0 | rvv | 128 | decode | 57258556042 | 6029037629 | 9.497 |
| q8_0 | rvv | 256 | prefill | 85987895009 | 8616768230 | 9.979 |
| q8_0 | rvv | 256 | decode | 57258556042 | 5860882788 | 9.770 |

这是动态指令数量比，不能当性能加速比。QEMU 执行时间受翻译和插件开销影响，没有作为 Breeze 或协处理器速度证据。

## 对设计的含义与限制

实测操作码、短块 VL 和配置频度可以作为下一步快路径与固定开销模型的输入；仅扩大 VLEN 是否减少现有内核指令，需看 Q3 的实际数据。归约、扩宽整数乘加和 Q4 拆包不能遗漏。模型含不同类型的输出/嵌入与 F32 张量，因此不能只建一种量化矩阵模型。架构访存量没有建立 DDR 带宽、延迟或在途深度的实测依据；这里不选择 DLEN、队列深度、自定义指令或 RTL 实现。W2 仍需用户确认。

q4_0：VLEN 从 128 到 1024，decode 总指令变化 -4.063%；VLEN=128 时上述两个量化 dot 的自身向量指令占阶段向量总量 91.474%。这两个比例直接由 Q1/Q2 的计数派生。

q8_0 的 RVV 1024 统计未完成，不能比较其 VLEN 128→1024 的总指令变化。

本次仅覆盖固定提示、16-token 贪心输出、单线程、GCC 15.1.0 和 rv64gcv。SEW/LMUL/VL 是执行时配置，whole-register 操作的实际传输大小以 memory 字节为准；符号计数为 exclusive。没有 full LLVM、Spike 长程序、Breeze 集成、RTL、FPGA、周期、PPA 或 DDR 实测结论。

## 一条命令复现

在 Alan 工作区运行以下命令，即重新生成本页全部统计表、逐张量清单和 Markdown。原始执行与两次再生、代表 case 的独立重跑比对见回报以及 RUN/pipeline.log、RUN/parallel-recovery.log。

```bash
python3 rvv/tools/write-profile.py /home/chen/FUN/flow-r01-runs/20261005-w1 /home/chen/FUN/flow-r01-runs/20261005-w1/presentation --partial
```

