package flow.platform

import chisel3._
import chisel3.util._

object PMAAccessType extends ChiselEnum {
  val Fetch, Load, Store = Value
}

/** One physical-memory access to classify.
  *
  * sizeLog2 encodes the complete transaction size in bytes. For example, 0 is
  * one byte, 3 is eight bytes, and 5 is one 32-byte cache line.
  */
class PMAQuery extends Bundle {
  val addr = UInt(64.W)
  val sizeLog2 = UInt(3.W)
  val accessType = PMAAccessType()
}

class PMAResult extends Bundle {
  val regionHit = Bool()
  val allowed = Bool()
  val cacheable = Bool()
  val device = Bool()
}

/** Static, purely combinational PMA checker for the Breeze MCU platform.
  *
  * A transaction is accepted only when every byte is contained in one active
  * region and the region supports the requested operation. Addresses outside
  * the configured physical width and holes in the map are denied by default.
  * Address alignment is checked by the execution/load-store path, not here.
  *
  * Instantiate this module independently on the instruction and data paths so
  * that PMA classification does not create a shared combinational bottleneck.
  */
class PMAChecker extends Module {
  val io = IO(new Bundle {
    val query = Input(new PMAQuery)
    val result = Output(new PMAResult)
  })

  private val regions = BreezeMcuPlatform.PMARegions
  private val addressWidth = BreezeMcuPlatform.AddressWidth
  private val addressMask = (BigInt(1) << addressWidth) - 1

  require(regions.nonEmpty, "PMA checker requires at least one active region")

  // The first implementation supports scalar accesses and cache-line checks up
  // to 32 bytes. Wider requests are safely denied.
  val sizeSupported = io.query.sizeLog2 <= 5.U
  val accessBytes = Wire(UInt(65.W))
  accessBytes := 1.U(65.W) << io.query.sizeLog2

  // An extra bit preserves overflow information while calculating the final
  // byte of the transaction.
  val startAddressExtended = Cat(0.U(1.W), io.query.addr)
  val lastAddressExtended = startAddressExtended + accessBytes - 1.U

  val startInsidePhysicalSpace = if (addressWidth == 64) {
    true.B
  } else {
    !io.query.addr(63, addressWidth).orR
  }

  val endInsidePhysicalSpace = if (addressWidth == 64) {
    !lastAddressExtended(64)
  } else {
    !lastAddressExtended(64, addressWidth).orR
  }

  val physicalAddressValid =
    sizeSupported && startInsidePhysicalSpace && endInsidePhysicalSpace
  val startAddress = io.query.addr(addressWidth - 1, 0)
  val lastAddress = lastAddressExtended(addressWidth - 1, 0)

  // Every configured region is power-of-two sized and naturally aligned. The
  // same mask must match both ends, which also rejects cross-region accesses.
  val regionMatches = VecInit(regions.map { region =>
    val regionMask = (~(region.size - 1)) & addressMask
    val mask = regionMask.U(addressWidth.W)
    val base = region.origin.U(addressWidth.W)

    physicalAddressValid &&
    ((startAddress & mask) === base) &&
    ((lastAddress & mask) === base)
  })

  val regionHit = regionMatches.asUInt.orR

  private def selectAttribute(attribute: PMARegionConst => Boolean): Bool =
    Mux1H(regions.indices.map(index => regionMatches(index) -> attribute(regions(index)).B))

  val supportsRead = selectAttribute(_.supportsRead)
  val supportsWrite = selectAttribute(_.supportsWrite)
  val supportsExecute = selectAttribute(_.supportsExecute)
  val cacheable = selectAttribute(_.cacheable)
  val device = selectAttribute(_.device)

  val accessSupported = MuxLookup(io.query.accessType, false.B)(Seq(
    PMAAccessType.Fetch -> supportsExecute,
    PMAAccessType.Load -> supportsRead,
    PMAAccessType.Store -> supportsWrite
  ))

  io.result.regionHit := regionHit
  io.result.allowed := regionHit && accessSupported
  io.result.cacheable := regionHit && cacheable
  io.result.device := regionHit && device
}
