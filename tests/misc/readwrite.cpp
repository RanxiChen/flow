//write 0 to 0x0 and then read
#include <stdint.h>
int main(void){
    uint32_t res=0xffffffff;
    asm(
        "sw x0, 0(x0)\n" //write 0 to 0x0
        "lw x1, 0(x0)\n" //read from 0x0
        : "=r"(res) //output x1
        : //no input
        : "memory" //memory is modified
    );
}