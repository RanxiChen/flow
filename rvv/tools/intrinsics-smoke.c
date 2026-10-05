#include <riscv_vector.h>
#include <stdint.h>
int main(void) {
    int8_t a[16], b[16];
    for (int i = 0; i < 16; ++i) a[i] = i;
    size_t vl = __riscv_vsetvl_e8m1(16);
    vint8m1_t x = __riscv_vle8_v_i8m1(a, vl);
    __riscv_vse8_v_i8m1(b, __riscv_vadd_vx_i8m1(x, 1, vl), vl);
    for (unsigned i = 0; i < vl; ++i) if (b[i] != a[i] + 1) return 1;
    return vl == 16 ? 0 : 2;
}
