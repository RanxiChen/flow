OUTPUT_ARCH(riscv)
ENTRY(_start)

MEMORY
{
    ROM  (rx)  : ORIGIN = 0x10000000, LENGTH = 8K
    SRAM (rwx) : ORIGIN = 0x11000000, LENGTH = 8K
}

SECTIONS
{
    .text : ALIGN(2)
    {
        KEEP(*(.text.start))
        *(.text .text.*)
        *(.rodata .rodata.*)
    } > ROM
    .data : ALIGN(8) { *(.data .data.*) } > SRAM AT> ROM
    .bss (NOLOAD) : ALIGN(8) { *(.bss .bss.*) *(COMMON) } > SRAM
    /DISCARD/ : { *(.comment) *(.eh_frame .eh_frame.*) *(.riscv.attributes) }
}

ASSERT(SIZEOF(.text) <= LENGTH(ROM), "Air boot image exceeds ROM")
