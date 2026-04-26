ENTRY(_start)

SECTIONS
{
  . = 0x0;

  .text : ALIGN(4) {
    *(.text)
  }

  /DISCARD/ : {
    *(.comment)
    *(.note*)
  }
}
