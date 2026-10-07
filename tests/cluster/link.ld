/* Cluster self-check programs: one RWX image at main_ram (CLUSTER-sim-abi §2).
 * No .bss: unloaded RAM holds harness background values, so every variable
 * lives in an initialized section. */
OUTPUT_ARCH("riscv")
ENTRY(_start)
SECTIONS
{
  . = 0x80000000;
  .text.init : { *(.text.init) }
  . = ALIGN(0x1000);
  .text : { *(.text) *(.text.*) }
  . = ALIGN(0x1000);
  .tohost : { *(.tohost) }
  . = ALIGN(0x1000);
  .data : { *(.data) *(.data.*) *(.sdata) *(.sdata.*) *(.rodata) *(.rodata.*) }
  .bss : { *(.bss) *(.sbss) *(COMMON) }
  ASSERT(SIZEOF(.bss) == 0, "cluster programs must not use .bss")
  _end = .;
}
