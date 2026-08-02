OUTPUT_ARCH(riscv)
ENTRY(_start)

MEMORY
{
    ROM  (rx) : ORIGIN = 0x10000000, LENGTH = 64K
    SRAM (rw) : ORIGIN = 0x11000000, LENGTH = 256K
}

SECTIONS
{
    .text : ALIGN(8)
    {
        KEEP(*(.text.start))
        KEEP(*(.text.trap_vector))
        KEEP(*(.text.trap_entry))
        *(.text .text.*)
    } > ROM

    .rodata : ALIGN(8)
    {
        *(.rodata .rodata.*)
    } > ROM

    .breeze_control (NOLOAD) : ALIGN(8)
    {
        __breeze_result = .;
        . += 8;
    } > SRAM

    .data : ALIGN(8)
    {
        _sdata = .;
        *(.data .data.*)
        . = ALIGN(8);
        _edata = .;
    } > SRAM AT> ROM
    _sidata = LOADADDR(.data);

    .bss (NOLOAD) : ALIGN(8)
    {
        _sbss = .;
        *(.bss .bss.*)
        *(COMMON)
        . = ALIGN(8);
        _ebss = .;
    } > SRAM

    /DISCARD/ :
    {
        *(.comment)
        *(.eh_frame .eh_frame.*)
        *(.riscv.attributes)
    }

    _stack_top = ORIGIN(SRAM) + LENGTH(SRAM);
}

ASSERT(_sidata + SIZEOF(.data) <= ORIGIN(ROM) + LENGTH(ROM),
       "ROM image exceeds the boot ROM")
ASSERT(_ebss <= _stack_top, "SRAM sections exceed the on-chip SRAM")
