package flow.mmu.sv39

case class Sv39TlbParams(sets: Int, ways: Int, superpages: Int) {
  Seq(sets, ways, superpages).foreach(n => require(n >= 1 && (n & (n - 1)) == 0))
  require(sets <= (1 << Sv39Constants.VpnBits))
}
case class Sv39MmuParams(
  asidBits: Int = 16,
  itlb: Sv39TlbParams = Sv39TlbParams(8, 4, 4),
  dtlb: Sv39TlbParams = Sv39TlbParams(8, 4, 4),
  wcUpperSets: Int = 1, wcUpperWays: Int = 4,
  wcMiddleSets: Int = 2, wcMiddleWays: Int = 4
) {
  require(asidBits == 16, "Sv39 uses the specified 16-bit ASID tags")
  Seq(wcUpperSets, wcUpperWays, wcMiddleSets, wcMiddleWays)
    .foreach(n => require(n >= 1 && (n & (n - 1)) == 0))
  require(wcUpperSets <= 512 && wcMiddleSets <= (1 << 18))
}
object Sv39Constants {
  val VLEN = 64
  val PgOffsetBits = 12
  val VpnBits = 27
  val VpnSliceBits = 9
  val PpnBits = 44
  val PaddrBits = 56
}
