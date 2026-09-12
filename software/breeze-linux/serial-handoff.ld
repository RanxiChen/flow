OUTPUT_ARCH(riscv)
ENTRY(_start)
SECTIONS {
    . = 0x80080000;
    .text : { KEEP(*(.text.entry)) *(.text*) }
    /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) }
}
ASSERT(SIZEOF(.text) <= 0x1000, "Serial handoff exceeds its reserved page")
