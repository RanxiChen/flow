package flow.top

import chisel3._
import flow.bus.{DCacheWishboneBridge, ICacheWishboneBridge, LiteXWishboneMasterIO, LiteXWishboneParameters}
import flow.cache.BreezeDCache
import flow.config.{BreezeCoreConfig, BreezeCoreConfigs}
import flow.core.BreezeCore
import flow.frontend.BreezeFrontendDebugIO
import flow.interface._
import flow.platform.BreezeMcuPlatform

/** LiteX-facing wrapper around the existing core.
  *
  * BreezeCore keeps its read-only cache-line pulse interface so that a future
  * L2 can replace this bridge without modifying the ICache. The wrapper exposes
  * independent standard 64-bit instruction and data Wishbone masters to LiteX.
  */
class BreezeCoreWishbone(
    val corecfg: BreezeCoreConfig = BreezeCoreConfigs.baseline(),
    val enabledebug: Boolean = false
) extends Module {
  private val iWishboneParams = LiteXWishboneParameters(
    byteAddressWidth = BreezeMcuPlatform.AddressWidth,
    dataWidth = 64
  )
  private val dWishboneParams = LiteXWishboneParameters(
    byteAddressWidth = BreezeMcuPlatform.AddressWidth,
    dataWidth = 64
  )

  val io = IO(new Bundle {
    val resetAddr = Input(UInt(corecfg.VLEN.W))
    val machineTimerInterrupt = Input(Bool())
    val externalInterrupts = Input(UInt(BreezeMcuPlatform.ExternalInterruptWidth.W))
    val iWishbone = new LiteXWishboneMasterIO(iWishboneParams)
    val dWishbone = new LiteXWishboneMasterIO(dWishboneParams)
    val dcacheFatalError = Output(Bool())
    val estop = Output(Bool())
    val fase = if (corecfg.useFASE) Some(new FASECoreIO()) else None
    val tandem = if (corecfg.enableTandem) Some(Output(new TracePayload(corecfg.VLEN))) else None
    val frontendDebug = if (enabledebug) Some(new BreezeFrontendDebugIO(corecfg.VLEN)) else None
    val debug = if (enabledebug) Some(new BackendDebugIO(corecfg.VLEN)) else None
  })

  val core = Module(new BreezeCore(corecfg, enabledebug = enabledebug))
  val iBridge = Module(new ICacheWishboneBridge(
    physicalAddressWidth = BreezeMcuPlatform.AddressWidth,
    lineBytes = corecfg.frontendCfg.cacheCfg.ICACHE_LINE_BYTES,
    busDataWidth = iWishboneParams.dataWidth
  ))
  val dcache = Module(new BreezeDCache(corecfg.dcacheCfg))
  val dBridge = Module(new DCacheWishboneBridge(
    physicalAddressWidth = BreezeMcuPlatform.AddressWidth,
    lineBytes = corecfg.dcacheCfg.lineBytes,
    busDataWidth = dWishboneParams.dataWidth
  ))

  core.io.resetAddr := io.resetAddr
  core.io.machineTimerInterrupt := io.machineTimerInterrupt
  core.io.externalInterrupts := io.externalInterrupts
  iBridge.io.cacheReq <> core.io.nextLevelReq
  iBridge.io.cacheResp <> core.io.nextLevelRsp
  io.iWishbone <> iBridge.io.wishbone

  dcache.io.cpu <> core.io.dmem
  dcache.io.flushReq := core.io.dcacheFlushReq
  core.io.dcacheFlushDone := dcache.io.flushDone
  dBridge.io.cacheReq <> dcache.io.nextLevelReq
  dBridge.io.cacheResp <> dcache.io.nextLevelRsp
  io.dWishbone <> dBridge.io.wishbone
  io.dcacheFatalError := dcache.io.fatalError
  io.estop := core.io.estop
  io.fase.zip(core.io.fase).foreach { case (wrapperFase, coreFase) =>
    wrapperFase <> coreFase
  }
  io.tandem.zip(core.io.tandem).foreach { case (wrapperTandem, coreTandem) =>
    wrapperTandem := coreTandem
  }
  io.frontendDebug.zip(core.io.frontendDebug).foreach { case (wrapperDebug, coreDebug) =>
    wrapperDebug <> coreDebug
  }
  io.debug.zip(core.io.debug).foreach { case (wrapperDebug, coreDebug) =>
    wrapperDebug <> coreDebug
  }
}
