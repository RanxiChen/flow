OUTPUT_ARCH(riscv)
ENTRY(_start)
SECTIONS {
  . = 0x80200000;
  .text : { *(.text.init) *(.text*) }
  .rodata : { *(.rodata*) }
  .data : { *(.data*) }
  . = ALIGN(16);
  PROVIDE(__global_pointer$ = . + 0x800);
  .sdata : { *(.sdata*) }
  . = ALIGN(8);
  __bss_start = .;
  .bss (NOLOAD) : { *(.sbss*) *(.bss*) *(COMMON) }
  . = ALIGN(8);
  __bss_end = .;
  . = ALIGN(16);
  . += 0x10000;
  stack_top = .;
  ASSERT(stack_top < 0x803ff000, "payload overlaps tohost")
  tohost = 0x803ff000;
}
