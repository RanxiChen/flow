OUTPUT_ARCH(riscv)
ENTRY(_start)

MEMORY
{
    ROM  (rx) : ORIGIN = 0x10010000, LENGTH = 64K
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
        __breeze_pmu_snapshot = .;
        . += 80;
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

    /* Keep the upper half of SRAM out of the normal data allocator.  P3 uses
     * the top 128 KiB for four possible 32 KiB hart stacks (dual consumes the
     * top two slices); the page immediately below is the boot publication
     * slot.  Reserving the maximum frozen 1/2/4-hart layout here makes a large
     * .bss fail at link time instead of silently colliding with a stack. */
    _breeze_boot_barrier = ORIGIN(SRAM) + 0x1f000;
    _stack_floor = ORIGIN(SRAM) + 0x20000;
    _stack_top = ORIGIN(SRAM) + LENGTH(SRAM);
}

ASSERT(_sidata + SIZEOF(.data) <= ORIGIN(ROM) + LENGTH(ROM),
       "ROM image exceeds the boot ROM")
ASSERT(_ebss <= _breeze_boot_barrier,
       "SRAM data/BSS overlaps the multicore boot-control page")
ASSERT(_breeze_boot_barrier + 8 <= _stack_floor,
       "multicore boot-control page overlaps the hart stacks")
