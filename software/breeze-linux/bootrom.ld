OUTPUT_ARCH(riscv)
ENTRY(_start)

SECTIONS
{
    . = 0x10010000;
    .text : { *(.text.init) *(.text*) }
    .rodata : { *(.rodata*) }
    /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) }
}

