OUTPUT_ARCH(riscv)
ENTRY(_start)

MEMORY
{
    ROM  (rx)  : ORIGIN = 0x10000000, LENGTH = 8K
    SRAM (rwx) : ORIGIN = 0x11000000, LENGTH = 8K
}

SECTIONS
{
    .text : ALIGN(4)
    {
        KEEP(*(.text.start))
        KEEP(*(.text.trap))
        *(.text .text.*)
        *(.rodata .rodata.*)
    } > ROM

    .data : ALIGN(8)
    {
        __data_start = .;
        *(.data .data.*)
        __data_end = .;
    } > SRAM AT> ROM
    __data_load_start = LOADADDR(.data);

    .bss (NOLOAD) : ALIGN(8)
    {
        __bss_start = .;
        *(.bss .bss.*) *(.sbss .sbss.*) *(COMMON)
        __bss_end = .;
    } > SRAM

    __stack_top = ORIGIN(SRAM) + LENGTH(SRAM);

    /DISCARD/ : { *(.comment) *(.eh_frame .eh_frame.*) *(.riscv.attributes) }
}

ASSERT(LOADADDR(.data) + SIZEOF(.data) <= ORIGIN(ROM) + LENGTH(ROM),
    "MCU ROM image exceeds 8 KiB")
ASSERT(__bss_end + 512 <= __stack_top, "MCU SRAM leaves no interrupt stack")
