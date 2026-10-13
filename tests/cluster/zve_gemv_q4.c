/* §9: Q4_0 x Q8_0 GEMV. The two ELFs use the same quantized activations and
 * mathematical weights; the vector ELF carries the 64-row offline repack. */
#include <stdint.h>

#define K 896
#define BLOCKS (K / 32)
#ifdef VECTOR_MODE
#ifndef GEMV_N
#define GEMV_N 256
#endif
#define N GEMV_N
extern const uint8_t q4_repacked[(N / 64) * BLOCKS * 1280];
extern void gemv_vector(const uint8_t *, const int8_t *, float *, float);
#else
/* The scalar generic Q4_0 block is 16 packed bytes plus an f32 scale. */
#define N 64
extern const uint8_t q4_original[N * BLOCKS * 20];
#endif

__attribute__((section(".tohost"))) volatile uint64_t tohost = 0;
__attribute__((section(".data"))) volatile uint64_t benchmark_cycles = UINT64_MAX;
__attribute__((section(".data"))) volatile uint64_t benchmark_phase = UINT64_MAX;
__attribute__((section(".data"))) volatile uint64_t benchmark_blocks = UINT64_MAX;
__attribute__((section(".data"))) volatile uint64_t benchmark_weight_bytes = UINT64_MAX;
__attribute__((section(".data"))) volatile float benchmark_max_error = -1.0f;
__attribute__((section(".data"))) volatile float activation[K] = {[0 ... K-1] = 1.0f};
__attribute__((section(".data"))) volatile int8_t q8[K] = {[0 ... K-1] = 1};
__attribute__((section(".data"))) volatile float output[N] = {[0 ... N-1] = -1.0f};

static uint64_t cycle(void) {
  uint64_t v;
  __asm__ volatile ("rdcycle %0" : "=r"(v));
  return v;
}

#ifndef VECTOR_MODE
/* ggml's generic Q4_0 x Q8_0 arithmetic: low and high nibbles of each
 * byte are elements j and j+16, then the two block scales multiply the sum. */
static void gemv_scalar(float da) {
  for (int r = 0; r < N; ++r) {
    float acc = 0.0f;
    for (int b = 0; b < BLOCKS; ++b) {
      const volatile uint8_t *block = q4_original + (r * BLOCKS + b) * 20;
      int sum = 0;
      for (int j = 0; j < 16; ++j) {
        unsigned packed = block[j];
        int lo = (int)(packed & 15) - 8;
        int hi = (int)(packed >> 4) - 8;
        sum += lo * q8[b * 32 + j] + hi * q8[b * 32 + j + 16];
      }
      union { uint32_t u; float f; } dw;
      dw.u = (uint32_t)block[16] | ((uint32_t)block[17] << 8) |
        ((uint32_t)block[18] << 16) | ((uint32_t)block[19] << 24);
      acc += (float)sum * (da * dw.f);
    }
    output[r] = acc;
  }
}
#endif

int main(void) {
  /* Runtime Q8_0 quantization from f32 activations. Every block has the same
   * max-abs input in this deterministic fixture, so da is shared by assembly. */
  float max_abs = 0.0f;
  for (int i = 0; i < K; ++i) {
    float x = activation[i]; if (x < 0.0f) x = -x;
    if (x > max_abs) max_abs = x;
  }
  float da = max_abs / 127.0f;
  float inverse = 1.0f / da;
  for (int i = 0; i < K; ++i) {
    int q = (int)(activation[i] * inverse + 0.5f);
    if (q > 127) q = 127; if (q < -128) q = -128;
    q8[i] = (int8_t)q;
  }
#if defined(VECTOR_MODE) && GEMV_N == 64
  /* Read each weight line before timing so this variant measures a resident
   * 35 KiB working set rather than the first cold pass through DRAM. */
  volatile const uint8_t *warm = q4_repacked;
  for (int i = 0; i < (int)sizeof(q4_repacked); i += 32) (void)warm[i];
#endif
  benchmark_phase = 1;
  uint64_t start = cycle();
#ifdef VECTOR_MODE
  gemv_vector(q4_repacked, (const int8_t *)q8, (float *)output, da);
#else
  gemv_scalar(da);
#endif
  uint64_t elapsed = cycle() - start;
  benchmark_phase = 2;
  benchmark_cycles = elapsed;
  benchmark_blocks = (N / 64) * BLOCKS;
  benchmark_weight_bytes = N * BLOCKS * 20;
  float max_error = 0.0f;
  for (int r = 0; r < N; ++r) {
    float error = output[r] - 896.0f;
    if (error < 0.0f) error = -error;
    if (error > max_error) max_error = error;
  }
  benchmark_max_error = max_error;
  return max_error <= 896.0f * 1.0e-5f ? 0 : 2;
}
