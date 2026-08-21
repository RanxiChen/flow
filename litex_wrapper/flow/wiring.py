"""Width-explicit Migen wiring helpers shared by Flow LiteX targets."""

from migen import Cat, Constant


def pack_plic_sources(source_vector, first_source=10, num_sources=31):
    """Place vector bit 0 at a one-based PLIC source without shifting."""
    width = len(source_vector)
    low_zeros = int(first_source) - 1
    high_zeros = int(num_sources) - low_zeros - width
    if low_zeros < 0 or high_zeros < 0:
        raise ValueError("interrupt vector does not fit in the PLIC source map")
    parts = []
    if low_zeros:
        parts.append(Constant(0, low_zeros))
    parts.append(source_vector)
    if high_zeros:
        parts.append(Constant(0, high_zeros))
    return Cat(*parts)


def wishbone_byte_address(word_address, data_width):
    """Convert a Wishbone word address to an explicitly widened byte address."""
    data_bytes = int(data_width) // 8
    if data_bytes < 1 or data_bytes & (data_bytes - 1):
        raise ValueError("Wishbone data width must contain a power-of-two byte count")
    byte_shift = (data_bytes - 1).bit_length()
    if byte_shift == 0:
        return word_address
    return Cat(Constant(0, byte_shift), word_address)
