#include <riscv_vector.h>
#include <stdint.h>
__attribute__((noinline)) void r01_phase_prefill(void) { asm volatile("" ::: "memory"); }
__attribute__((noinline)) void r01_phase_decode(void) { asm volatile("" ::: "memory"); }
__attribute__((noinline)) void r01_phase_end(void) { asm volatile("" ::: "memory"); }
__attribute__((noinline)) void copy16(const int8_t *src, int8_t *dst) {
    size_t vl = __riscv_vsetvl_e8m1(16);
    __riscv_vse8_v_i8m1(dst, __riscv_vle8_v_i8m1(src, vl), vl);
}
int main(void) {
    int8_t a[16], b[16], c[16];
    for (int i = 0; i < 16; ++i) a[i] = i;
    r01_phase_prefill();
    copy16(a, b);
    r01_phase_decode();
    copy16(b, c);
    r01_phase_end();
    for (int i = 0; i < 16; ++i) if (c[i] != i) return 1;
    return 0;
}
