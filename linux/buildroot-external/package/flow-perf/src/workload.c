#include <stdint.h>
#include <stdio.h>
#include <string.h>

__attribute__((noinline)) uint64_t hot_integer(uint64_t x) {
    return x*UINT64_C(6364136223846793005)+UINT64_C(1442695040888963407);
}
__attribute__((noinline)) uint64_t hot_branch(uint64_t x) {
    if (x&1) return x^(x>>11);
    return x+UINT64_C(0x9e3779b97f4a7c15);
}
extern uint64_t flow_perf_icache_hot(uint64_t);
int main(int argc, char **argv) {
    uint64_t x, expected;
    if (argc==2 && !strcmp(argv[1],"--icache")) {
        x=7;
        for(unsigned i=0;i<64;++i) x=flow_perf_icache_hot(x);
        expected=7+64*8192;
    } else {
        x=0x12345678;
        for(unsigned i=0;i<2000000;++i) x=hot_branch(hot_integer(x));
        expected=UINT64_C(0x8d0342c3e9ff3d35);
    }
    printf("flow-perf-workload checksum=%016llx %s\n",(unsigned long long)x,x==expected?"PASS":"FAIL");
    return x!=expected;
}
