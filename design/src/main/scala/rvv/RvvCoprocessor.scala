package flow.rvv

import chisel3._
import chisel3.util._

class RvvCoprocessor(val p: RvvParams = RvvParams()) extends Module {
  val io = IO(new Bundle {
    val issue = Flipped(Decoupled(new RvvIssue(p)))
    val verdict = Decoupled(new RvvVerdict(p))
    val commit = Input(Bool()); val killUncommitted = Input(Bool()); val serialGo = Input(Bool())
    val translation = Decoupled(new RvvTranslationRequest(p))
    val translated = Flipped(Decoupled(new RvvTranslationResponse(p)))
    val scalarResult = Decoupled(new RvvScalar)
    val drained = Output(Bool()); val vxsat = Output(Bool()); val fflags = Output(UInt(5.W))
    val conflictQuery = Input(new RvvConflict); val conflict = Output(Bool())
    val invalidate = Decoupled(UInt(64.W)); val scalarWritesVisible = Input(Bool())
    val axi = new RvvAxi(p); val counters = Output(new RvvCounters)
    // Passive measurement events; they do not control the mounted core model.
    val loadRegisterComplete = Valid(new RvvRegisterEvent)
    val macRead = Valid(new RvvRegisterEvent); val macComplete = Valid(UInt(64.W))
    val loadWriteWar = Output(Bool())
  })
  val macReady = IO(Output(Valid(new RvvRegisterEvent)))
  val macBlocking = IO(Output(UInt(4.W)))
  val dispatchEvent = IO(Output(Valid(UInt(p.ageBits.W))))
  val prefetchEvent = IO(Output(Valid(UInt(p.ageBits.W))))
  val dispatchMacFull = IO(Output(Bool()))
  val front = Module(new RvvFrontend(p))
  front.io.issue <> io.issue; io.verdict <> front.io.verdict
  front.io.commit := io.commit; front.io.kill := io.killUncommitted; front.io.serialGo := io.serialGo
  io.translation <> front.io.translation; front.io.translated <> io.translated
  front.io.scalarQuery := io.conflictQuery; io.conflict := front.io.scalarConflict
  val sb = Module(new RvvScoreboard(p,6))
  sb.io.nextAge := front.io.nextAge; val memAgeAllowed = Wire(Bool())
  front.io.externalAgeAllowed := sb.io.ageAllowed && memAgeAllowed
  val queues = p.unitDepths.map(depth => Module(new Queue(new RvvDescriptor(p),depth)))
  val dispatch = front.io.dispatch
  val qReady = VecInit(queues.map(_.io.enq.ready))(dispatch.bits.decoded.unit)
  dispatch.ready := qReady && sb.io.allocate.ready
  dispatchEvent.valid := dispatch.fire; dispatchEvent.bits := dispatch.bits.age
  dispatchMacFull := dispatch.valid && dispatch.bits.decoded.unit === 2.U && !queues(2).io.enq.ready
  sb.io.allocate.valid := dispatch.valid && qReady; sb.io.allocate.bits := dispatch.bits
  for((q,j) <- queues.zipWithIndex) {
    q.io.enq.valid := dispatch.valid && sb.io.allocate.ready && dispatch.bits.decoded.unit === j.U
    q.io.enq.bits := dispatch.bits; q.io.enq.bits.slot := sb.io.slot
  }
  val mem = Module(new RvvLoadStore(p)); mem.io.in <> queues(0).io.deq
  mem.io.prefetch <> front.io.prefetch
  prefetchEvent := mem.io.prefetchEvent
  mem.io.nextAge := front.io.nextAge; memAgeAllowed := mem.io.ageAllowed
  val alu = Module(new RvvIntegerSequencer(p,false)); alu.io.in <> queues(1).io.deq
  val mac = Module(new RvvIntegerSequencer(p,true)); mac.io.in <> queues(2).io.deq
  val fp = Module(new RvvFpSequencer(p)); fp.io.in <> queues(3).io.deq
  val cross = Module(new RvvCrossLaneSequencer(p)); cross.io.in <> queues(4).io.deq
  io.axi <> mem.io.axi; io.invalidate <> mem.io.invalidate
  mem.io.scalarWritesVisible := io.scalarWritesVisible
  front.io.vectorQuery := mem.io.ordering; front.io.vectorQueryValid := mem.io.orderingValid
  mem.io.conflict := front.io.vectorConflict; front.io.released <> mem.io.released
  io.scalarResult <> cross.io.result
  macReady := mac.io.readyEvent; macBlocking := mac.io.blocking
  io.macRead <> mac.io.readEvent
  io.macComplete := RegNext(mac.io.complete)
  io.macComplete.valid := RegNext(mac.io.complete.valid,false.B)
  io.loadRegisterComplete.valid := RegNext(mem.io.progress(1).valid && mem.io.progress(1).bits.writeDone.orR,false.B)
  io.loadRegisterComplete.bits.age := RegNext(mem.io.writeAge)
  io.loadRegisterComplete.bits.register := RegNext(PriorityEncoder(mem.io.progress(1).bits.writeDone))
  val vrf = Module(new RvvRegisterFile(p))
  alu.io.mask := vrf.io.mask; mac.io.mask := vrf.io.mask
  // Allocate the shared execution read ports by age when demand exceeds supply.
  val bothFit = alu.io.readDemand +& mac.io.readDemand <= p.execReadPorts.U
  alu.io.grant := !mac.io.busy || bothFit || RvvAge.older(alu.io.age,mac.io.age)
  mac.io.grant := !alu.io.busy || bothFit || RvvAge.older(mac.io.age,alu.io.age)
  vrf.io.readValid.foreach(_ := false.B)
  vrf.io.readRows.foreach(_ := 0.U)
  alu.io.readData.foreach(_ := 0.U); mac.io.readData.foreach(_ := 0.U)
  val macBase = Mux(alu.io.busy && alu.io.grant,alu.io.readDemand,0.U)
  val macDataBase = RegNext(RegEnable(macBase,mac.io.readValid))
  for(j <- 0 until 3) {
    alu.io.readData(j) := vrf.io.readData(j)
    mac.io.readData(j) := vrf.io.readData((macDataBase+&j.U)(log2Ceil(p.execReadPorts+2)-1,0))
    when(alu.io.grant && j.U < alu.io.readDemand) {
      vrf.io.readRows(j) := alu.io.readRows(j); vrf.io.readValid(j) := alu.io.readValid
    }
    val index = (macBase+&j.U).pad(log2Ceil(p.execReadPorts+2))
    when(mac.io.grant && j.U < mac.io.readDemand) {
      vrf.io.readRows(index) := mac.io.readRows(j); vrf.io.readValid(index) := mac.io.readValid && !(if(j == 1) mac.io.skipAccumulator else false.B)
    }
  }
  vrf.io.readValid(p.execReadPorts) := mem.io.storeReadValid
  vrf.io.readValid(p.execReadPorts+1) := cross.io.readValid
  vrf.io.readRows(p.execReadPorts) := mem.io.storeRow; mem.io.storeData := vrf.io.readData(p.execReadPorts)
  vrf.io.readRows(p.execReadPorts+1) := cross.io.row; cross.io.data := vrf.io.readData(p.execReadPorts+1)
  vrf.io.write(0) <> mem.io.write; vrf.io.age(0) := mem.io.writeAge
  vrf.io.write(1) <> alu.io.write; vrf.io.age(1) := alu.io.writeAge
  vrf.io.write(2) <> mac.io.write; vrf.io.age(2) := mac.io.writeAge
  for(unit <- Seq(alu,mac); j <- 0 until 2) {
    unit.snoop(j).valid := vrf.io.write(j).fire
    unit.snoop(j).bits.row := vrf.io.write(j).bits.row
    unit.snoop(j).bits.age := vrf.io.age(j)
  }
  val checks = Seq(mem.io.hazard(0),mem.io.hazard(1),alu.io.hazard,mac.io.hazard,cross.io.hazard,0.U.asTypeOf(new RvvHazard(p)))
  val updates = Seq(mem.io.progress(0),mem.io.progress(1),alu.io.progress,mac.io.progress,cross.io.progress)
  checks.zipWithIndex.foreach { case(x,j) => sb.io.check(j) := x }
  updates.zipWithIndex.foreach { case(x,j) =>
    if(j == 1 || j == 2 || j == 3) { sb.io.progress(j) := RegNext(x); sb.io.progress(j).valid := RegNext(x.valid,false.B) }
    else sb.io.progress(j) := x
  }
  sb.io.progress(5) := RegNext(RegNext(mac.io.readProgress))
  sb.io.progress(5).valid := RegNext(RegNext(mac.io.readProgress.valid,false.B),false.B)
  for((unit,j) <- Seq((alu,2),(mac,3))) {
    unit.io.accumulatorPending := sb.io.dotAccumulatorPending(j)
    unit.io.otherRawBlocked := sb.io.rawExceptObserved(j)
    unit.io.rawBlocked := sb.io.raw(j); unit.io.warBlocked := sb.io.war(j); unit.io.wawBlocked := sb.io.waw(j)
  }
  mem.io.blocked(0) := sb.io.raw(0) || sb.io.war(0) || sb.io.waw(0)
  mem.io.blocked(1) := sb.io.raw(1) || sb.io.war(1) || sb.io.waw(1)
  mem.io.writeWar := sb.io.war(1)
  io.loadWriteWar := sb.io.war(1) && mem.io.hazard(1).valid
  alu.io.blocked := sb.io.raw(2) || sb.io.war(2) || sb.io.waw(2)
  mac.io.blocked := sb.io.raw(3) || sb.io.war(3) || sb.io.waw(3)
  cross.io.blocked := sb.io.raw(4) || sb.io.war(4) || sb.io.waw(4)
  io.drained := front.io.empty && queues.map(_.io.count === 0.U).reduce(_ && _) &&
    sb.io.empty && !mem.io.busy && !alu.io.busy && !mac.io.busy && !fp.io.busy && !cross.io.busy
  io.vxsat := false.B; io.fflags := 0.U
  io.counters := 0.U.asTypeOf(new RvvCounters)
  io.counters.readBytes := mem.io.readBytes; io.counters.writeBytes := mem.io.writeBytes
  io.counters.bufferedBytes := mem.io.bufferedBytes; io.counters.war := mem.io.war
  io.counters.overlap := mem.io.overlap; io.counters.credit := mem.io.credit; io.counters.axi := mem.io.axiStall
  io.counters.invalidations := mem.io.invalidations; io.counters.serial := front.io.serialCount
  val unitBusy = Seq(mem.io.busy,alu.io.busy,mac.io.busy,fp.io.busy,cross.io.busy)
  val mapping = Seq(Seq(0,1),Seq(2),Seq(3),Seq.empty[Int],Seq(4))
  for(j <- 0 until 5) {
    def count(event: Bool): UInt = { val x = RegInit(0.U(64.W)); when(event) { x := x+1.U }; x }
    io.counters.busy(j) := count(unitBusy(j))
    io.counters.rawStall(j) := count(mapping(j).map(sb.io.raw(_)).foldLeft(false.B)(_ || _))
    io.counters.warStall(j) := count(mapping(j).map(sb.io.war(_)).foldLeft(false.B)(_ || _))
    io.counters.wawStall(j) := count(mapping(j).map(sb.io.waw(_)).foldLeft(false.B)(_ || _))
  }
}

object GenerateRvv extends App {
  val variant = args.headOption.getOrElse("512-512")
  val p = variant match {
    case "512-512" => RvvParams()
    case "256-256" => RvvParams(vlen=256,dlen=256,lanes=4,memoryBits=256)
    case "512-256" => RvvParams(vlen=512,dlen=256,lanes=4,memoryBits=256)
    case other => sys.error(s"unknown R02 configuration $other")
  }
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(new RvvCoprocessor(p),
    Array("--target-dir",args.lift(1).getOrElse(s"generated/rvv/$variant")),
    Array("-disable-all-randomization","-strip-debug-info"))
}
