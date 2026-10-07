OUTPUT_ARCH(riscv)
ENTRY(_start)
SECTIONS {
  . = 0x80000000;
  .text : { *(.text*) }
  . = 0x81000000;
  .data : { *(.data*) }
  . = ALIGN(4096);
  .tohost : { *(.tohost*) }
}
