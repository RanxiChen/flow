package flow.config

import chisel3.util.isPow2

/** v1 memory-system geometry (l1d-rtl-spec §0.2).
  *
  * SKELETON: lives in its own file so this branch does not collide with the
  * backend work in config.scala. At merge, BreezeClusterConfig gains
  * `val mem: BreezeMemGeometry` and numHarts is renamed nCores; every module
  * keeps taking BreezeMemGeometry, so only the construction site changes.
  */
final case class BreezeMemGeometry(
    nCores: Int = 4,
    lineBytes: Int = 32,
    l1Sets: Int = 128,
    l1dWays: Int = 4,
    l1iWays: Int = 4,
    l1dMshrs: Int = 1,
    l2Ways: Int = 8,
    l2BytesPerCore: Int = 65536,
    l2Slots: Int = 2,
    paddrBits: Int = 32
) {
  require(nCores >= 1 && nCores <= 8, s"nCores=$nCores")
  require(lineBytes == 32, "v1 locks lineBytes=32 (one 256-bit link beat)")
  require(isPow2(l1Sets) && l1Sets * lineBytes <= 4096, "VIPT without aliasing")
  require(isPow2(l1dWays) && isPow2(l1iWays))
  require(l1dMshrs == 1, "v1 locks one MSHR; structure is written for N")
  require(isPow2(l2Ways) && isPow2(l2BytesPerCore))
  require(isPow2(nCores * l2BytesPerCore / (l2Ways * lineBytes)), "l2Sets must be a power of two")
  require(l2Slots >= 1)
  require(paddrBits == 32, "v1 locks paddrBits=32")
}

object BreezeMemGeometry {
  val default: BreezeMemGeometry = BreezeMemGeometry()
  /** l1d-rtl-spec §0.3 non-default smoke configurations. */
  val l2FourWay: BreezeMemGeometry = BreezeMemGeometry(l2Ways = 4)
  val l1dTwoWay: BreezeMemGeometry = BreezeMemGeometry(l1dWays = 2)
  val singleCore: BreezeMemGeometry = BreezeMemGeometry(nCores = 1)
  /** Tiny caches for coherence stress: forces victims and probe races. */
  val stress: BreezeMemGeometry =
    BreezeMemGeometry(l1Sets = 2, l1dWays = 1, l1iWays = 1, l2Ways = 2, l2BytesPerCore = 64)
}
