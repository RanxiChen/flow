ENTRY(_start)

SECTIONS
{
  . = 0x0;

  .text : ALIGN(4) {
    *(.text.init)
    *(.text)
    *(.text.*)
  }

  .rodata : ALIGN(8) {
    *(.rodata)
    *(.rodata.*)
  }

  .data : ALIGN(8) {
    *(.data)
    *(.data.*)
  }

  .bss (NOLOAD) : ALIGN(8) {
    *(.bss)
    *(.bss.*)
    *(COMMON)
  }

  /DISCARD/ : {
    *(.comment)
    *(.note*)
  }
}
