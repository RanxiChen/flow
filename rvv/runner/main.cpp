#include "llama.h"
#include <cstdio>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

// ELF-visible boundaries for the profiler; no upstream kernel changes.
extern "C" __attribute__((noinline)) void r01_phase_prefill() { asm volatile("" ::: "memory"); }
extern "C" __attribute__((noinline)) void r01_phase_decode() { asm volatile("" ::: "memory"); }
extern "C" __attribute__((noinline)) void r01_phase_end() { asm volatile("" ::: "memory"); }

static void set_batch(llama_batch_ext *batch, const llama_token *ids, int n, int start) {
    llama_batch_ext_clear(batch);
    for (int i = 0; i < n; ++i) {
        int idx = llama_batch_ext_add_token(batch, 0, ids[i]);
        llama_pos pos = start + i;
        llama_batch_ext_set_pos(batch, idx, &pos);
    }
    llama_batch_ext_set_output_logits(batch, n - 1, true);
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: r01-runner MODEL PROMPT_FILE\n");
        return 2;
    }
    std::ifstream in(argv[2]);
    if (!in) return 2;
    std::string prompt((std::istreambuf_iterator<char>(in)), {});
    if (!prompt.empty() && prompt.back() == '\n') prompt.pop_back();
    llama_backend_init();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    auto *model = llama_model_load_from_file(argv[1], mp);
    if (!model) return 3;
    auto *vocab = llama_model_get_vocab(model);
    int n = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
    if (n <= 0) return 4;
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), n, true, true) != n) return 4;
    fprintf(stderr, "R01_PROMPT_TOKENS=%d\n", n);
    auto cp = llama_context_default_params();
    cp.n_ctx = 256;
    cp.n_batch = cp.n_ubatch = 128;
    cp.n_threads = cp.n_threads_batch = 1;
    cp.no_perf = false;
    auto *ctx = llama_init_from_model(model, cp);
    if (!ctx) return 5;
    auto *batch = llama_batch_ext_init(ctx);
    auto *sampler = llama_sampler_init_greedy();
    set_batch(batch, tokens.data(), n, 0);
    r01_phase_prefill();
    if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch)) return 6;
    // First sample is included in prefill. Decode comprises 15 single-token
    // evaluations plus their samples, giving exactly 16 generated token IDs.
    std::vector<llama_token> generated;
    generated.push_back(llama_sampler_sample(sampler, ctx, -1));
    r01_phase_decode();
    for (int i = 1; i < 16; ++i) {
        set_batch(batch, &generated.back(), 1, n + i - 1);
        if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch)) return 6;
        generated.push_back(llama_sampler_sample(sampler, ctx, -1));
    }
    r01_phase_end();
    printf("{");
    printf("\"prompt_tokens\":%d,\"token_ids\":[", n);
    for (size_t i = 0; i < generated.size(); ++i) printf("%s%d", i ? "," : "", generated[i]);
    printf("]}\n");
    llama_batch_ext_free(batch);
    llama_sampler_free(sampler);
    llama_free(ctx);
    llama_model_free(model);
    llama_backend_free();
    return 0;
}
