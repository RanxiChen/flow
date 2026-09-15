package flow.fase

import chisel3._
import chisel3.util._

/** Seven payload words; the recorder prefixes the source-cycle timestamp. */
class FlightEvent extends Bundle {
  val valid = Bool()
  val words = Vec(7, UInt(64.W))
}

/** Passive, independently banked synchronous memories. No CPU backpressure.
  * 16 STATUS, 17 CONTROL, 18 MATCH_ADDRESS, 19 READ_WORD, 20 MATCH_MASK.
  * READ data: bank[15:13], slot[12:3], word[2:0]. Read only while frozen.
  */
class FlightRecorder(val depth: Int = 1024) extends Module {
  require(depth >= 2 && depth <= 1024 && isPow2(depth))
  val banks = 6
  val aw = log2Ceil(depth)
  val io = IO(new Bundle {
    val events = Input(Vec(banks, new FlightEvent))
    val privilege = Input(UInt(2.W))
    val host = new FaseCommandIO
    val probes = Output(Vec(8, UInt(64.W)))
  })
  val cycle = RegInit(0.U(64.W)); cycle := cycle + 1.U
  val active = RegInit(false.B)
  val triggered = RegInit(false.B)
  val triggerCycle = RegInit(0.U(64.W))
  val faultEnable = RegInit(false.B)
  val addressEnable = RegInit(false.B)
  val privMask = RegInit(15.U(4.W))
  val matchAddress = RegInit(0.U(64.W))
  val matchMask = RegInit("hffffffffffffffff".U(64.W))
  val postCycles = RegInit(32.U(16.W))
  val remaining = RegInit(0.U(16.W))
  val pointers = RegInit(VecInit(Seq.fill(banks)(0.U(aw.W))))
  val counts = RegInit(VecInit(Seq.fill(banks)(0.U((aw+1).W))))
  val totals = RegInit(VecInit(Seq.fill(banks)(0.U(32.W))))
  val triggerPointers = RegInit(VecInit(Seq.fill(banks)(0.U(aw.W))))
  // Sample fields together. RAM address/counter logic is off CPU control paths.
  val sampled = RegNext(io.events, 0.U.asTypeOf(io.events))
  val sampledValid = RegNext(VecInit(io.events.map(_.valid)), VecInit(Seq.fill(banks)(false.B)))
  val sampledCycle = RegNext(cycle, 0.U)
  val writes = Wire(Vec(banks, Bool()))
  val replyValid = RegInit(false.B)
  val replyError = RegInit(false.B)
  val replyData = RegInit(0.U(64.W))
  val readPending = RegInit(false.B)
  io.host.cmd.ready := !replyValid && !readPending
  io.host.rsp.valid := replyValid
  io.host.rsp.bits.error := replyError
  io.host.rsp.bits.data := replyData
  when(io.host.rsp.fire) { replyValid := false.B }
  val fire = io.host.cmd.fire
  val op = io.host.cmd.bits.opcode
  val arg = io.host.cmd.bits.data
  val bank = arg(15,13)
  val slot = arg(12,3)
  val readOK = !active && bank < banks.U && slot < depth.U && slot < counts(bank)
  val readFire = fire && op === 19.U && readOK
  val readBank = RegEnable(bank, readFire)
  val readWord = RegEnable(arg(2,0), readFire)
  val readOutputs = Wire(Vec(banks, Vec(8, UInt(64.W))))
  val badControl = PopCount(arg(2,0)) > 1.U
  val clear = fire && op === 17.U && !badControl && (arg(0) || arg(2))
  for (b <- 0 until banks) {
    val ram = SyncReadMem(depth, Vec(8, UInt(64.W)))
    val record = Wire(Vec(8, UInt(64.W)))
    record(0) := sampledCycle
    for (w <- 0 until 7) record(w+1) := sampled(b).words(w)
    // Actual privilege in the cycle after the sampled event (not a prediction).
    record(1) := Cat(io.privilege, sampled(b).words(0)(61,0))
    writes(b) := active && sampledValid(b) && !clear
    when(writes(b)) {
      ram.write(pointers(b), record)
      pointers(b) := pointers(b) + 1.U
      when(counts(b) =/= depth.U) { counts(b) := counts(b) + 1.U }
      totals(b) := totals(b) + 1.U
    }
    readOutputs(b) := ram.read(slot(aw-1,0), readFire && bank === b.U)
    when(clear) { pointers(b) := 0.U; counts(b) := 0.U; totals(b) := 0.U; triggerPointers(b) := 0.U }
  }
  val triggerPriv = sampled(5).words(0)(9,8)
  val fault = sampledValid(5) && sampled(5).words(0)(1) && sampled(5).words(0)(2)
  val address = sampledValid(5) && sampled(5).words(0)(0) &&
    ((sampled(5).words(1) & matchMask) === (matchAddress & matchMask))
  val hit = active && !clear && !triggered && privMask(triggerPriv) &&
    ((faultEnable && fault) || (addressEnable && address))
  when(hit) {
    triggered := true.B; triggerCycle := sampledCycle
    triggerPointers := pointers
    remaining := postCycles
    when(postCycles === 0.U) { active := false.B }
  }.elsewhen(active && triggered) {
    when(remaining <= 1.U) { active := false.B }.otherwise { remaining := remaining - 1.U }
  }
  readPending := readFire
  when(readPending) { replyValid := true.B; replyError := false.B; replyData := readOutputs(readBank)(readWord) }
  when(fire) {
    replyValid := !readFire
    replyError := false.B; replyData := 0.U
    switch(op) {
      is(16.U) {
        val idx = io.host.cmd.bits.index
        replyData := Cat(0.U(32.W), depth.U(16.W), banks.U(8.W), 0.U(5.W), triggered, !active, active)
        when(idx >= 1.U && idx <= banks.U) {
          val b = (idx - 1.U)(2,0)
          replyData := (totals(b) << 32) | (counts(b) << 16) | pointers(b)
        }.elsewhen(idx === 7.U) { replyData := triggerCycle }
          .elsewhen(idx >= 8.U && idx < 14.U) { replyData := triggerPointers((idx-8.U)(2,0)) }
          .elsewhen(idx =/= 0.U) { replyError := true.B }
      }
      is(17.U) {
        when(badControl) { replyError := true.B }
        .otherwise {
          faultEnable := arg(3); addressEnable := arg(4); privMask := arg(11,8)
          postCycles := arg(31,16)
          when(arg(0)) { active := true.B; triggered := false.B; triggerCycle := 0.U }
          when(arg(1) || arg(2)) { active := false.B }
          when(arg(2)) { triggered := false.B; triggerCycle := 0.U }
        }
      }
      is(18.U) { matchAddress := arg }
      is(19.U) { when(!readOK) { replyError := true.B } }
      is(20.U) { matchMask := arg }
    }
    when(op < 16.U || op > 20.U) { replyError := true.B }
  }
  io.probes(0) := sampledCycle
  io.probes(1) := Cat(0.U(53.W), sampledValid.asUInt, hit, triggered, !active, active, clear)
  io.probes(2) := sampled(1).words(2) // final backend redirect
  io.probes(3) := sampled(4).words(2) // actual frontend next PC
  io.probes(4) := sampled(5).words(1) // accepted IF translation VA
  io.probes(5) := sampled(5).words(2) // IF response VA
  io.probes(6) := sampled(0).words(1) // trap/return source PC
  io.probes(7) := sampled(5).words(0) // IF handshake/fault/context flags
}

/** One outstanding host command; route recorder commands without changing
  * the existing CPU command protocol or requiring injected loads for reads. */
class FaseRecorderRouter extends Module {
  val io = IO(new Bundle {
    val host = new FaseCommandIO
    val cpu = Flipped(new FaseCommandIO)
    val recorder = Flipped(new FaseCommandIO)
  })
  val busy = RegInit(false.B)
  val selected = RegInit(false.B)
  val choose = io.host.cmd.bits.opcode >= 16.U
  io.cpu.cmd.bits := io.host.cmd.bits; io.recorder.cmd.bits := io.host.cmd.bits
  io.cpu.cmd.valid := io.host.cmd.valid && !busy && !choose
  io.recorder.cmd.valid := io.host.cmd.valid && !busy && choose
  io.host.cmd.ready := !busy && Mux(choose, io.recorder.cmd.ready, io.cpu.cmd.ready)
  io.host.rsp.valid := busy && Mux(selected, io.recorder.rsp.valid, io.cpu.rsp.valid)
  io.host.rsp.bits := Mux(selected, io.recorder.rsp.bits, io.cpu.rsp.bits)
  io.cpu.rsp.ready := busy && !selected && io.host.rsp.ready
  io.recorder.rsp.ready := busy && selected && io.host.rsp.ready
  when(io.host.cmd.fire) { busy := true.B; selected := choose }
  when(io.host.rsp.fire) { busy := false.B }
}
