package flow.fase

import chisel3._
import chisel3.util._

/** Seven payload words; the recorder prefixes the source-cycle timestamp. */
class FlightEvent extends Bundle {
  val valid = Bool()
  val words = Vec(7, UInt(64.W))
}

/** v2: reset starts recording. Every committed instruction-page-fault creates
  * a protected snapshot, irrespective of privilege or fault address. No ILA,
  * CPU halt, automatic global freeze or pre-boot host configuration is needed.
  */
class FlightRecorder(val depth: Int = 4096, val pageSize: Int = 64,
                     val preEntries: Int = 256, val postCycles: Int = 64,
                     val snapshots: Int = 8) extends Module {
  require(isPow2(snapshots) && snapshots >= 2 && snapshots <= 8)
  require(depth <= 65535 && preEntries + postCycles + 1 <= 1024 && postCycles >= 1)
  val banks = 6
  private val sw = log2Ceil(snapshots)
  val io = IO(new Bundle {
    val events = Input(Vec(banks, new FlightEvent))
    val privilege = Input(UInt(2.W))
    val host = new FaseCommandIO
    val probes = Output(Vec(8, UInt(64.W)))
  })
  val cycle = RegInit(0.U(64.W)); cycle := cycle + 1.U
  val sampled = RegNext(io.events, 0.U.asTypeOf(io.events))
  val sampledCycle = RegNext(cycle, 0.U)
  val valid = RegInit(VecInit(Seq.fill(snapshots)(false.B)))
  val remaining = RegInit(VecInit(Seq.fill(snapshots)(0.U(log2Ceil(postCycles+1).W))))
  val ids = RegInit(VecInit(Seq.fill(snapshots)(0.U(64.W))))
  val manual = RegInit(VecInit(Seq.fill(snapshots)(false.B)))
  val headers = RegInit(VecInit(Seq.fill(snapshots)(VecInit(Seq.fill(8)(0.U(64.W))))))
  val nextSlot = RegInit(0.U(sw.W))
  val captures = RegInit(0.U(64.W))
  val evicted = RegInit(0.U(64.W))
  val latestCycle = RegInit(0.U(64.W))
  val leaseValid = RegInit(false.B)
  val leaseSlot = RegInit(0.U(sw.W))
  val replyValid = RegInit(false.B)
  val replyError = RegInit(false.B)
  val replyData = RegInit(0.U(64.W))
  val readPending = RegInit(false.B)
  val replyPending = RegNext(readPending, false.B)
  io.host.cmd.ready := !replyValid && !readPending && !replyPending
  io.host.rsp.valid := replyValid
  io.host.rsp.bits.error := replyError
  io.host.rsp.bits.data := replyData
  when(io.host.rsp.fire) { replyValid := false.B }
  val fire = io.host.cmd.fire
  val op = io.host.cmd.bits.opcode
  val arg = io.host.cmd.bits.data
  val index = io.host.cmd.bits.index
  val clear = fire && op === 23.U
  val selectMatches = VecInit((0 until snapshots).map(s => valid(s) && remaining(s) === 0.U && ids(s) === arg))
  val selectOK = selectMatches.asUInt.orR
  val selectedSlot = PriorityEncoder(selectMatches)
  val selecting = fire && op === 18.U && selectOK
  // Same-cycle SELECT wins over eviction. A reader's lease persists until
  // RELEASE/CAPTURE/SELECT/CLEAR; new faults use the other slots.
  val effectiveLease = leaseValid || selecting
  val effectiveSlot = Mux(selecting, selectedSlot, leaseSlot)
  val captureSlot = Mux(effectiveLease && nextSlot === effectiveSlot, nextSlot + 1.U, nextSlot)(sw-1,0)
  val fault = sampled(0).valid && sampled(0).words(0)(0) &&
    !sampled(0).words(0)(1) && sampled(0).words(3) === 12.U
  val manualRequest = fire && op === 21.U
  val capture = (fault || manualRequest) && !clear
  val extending = VecInit((0 until snapshots).map(s => valid(s) && remaining(s) =/= 0.U))
  val history = Seq.fill(banks)(Module(new FlightHistoryBank(depth, pageSize, preEntries, postCycles, snapshots)))
  val bank = arg(15,13)
  val entry = arg(12,3)
  val readCounts = VecInit(history.map(_.io.counts(leaseSlot)))
  val readOK = leaseValid && remaining(leaseSlot) === 0.U && bank < banks.U && entry < readCounts(bank)
  val readFire = fire && op === 19.U && readOK
  val readBank = RegEnable(bank, readFire)
  val readWord = RegEnable(arg(2,0), readFire)
  val wordData = Reg(Vec(banks, UInt(64.W)))
  for (b <- 0 until banks) {
    val h = history(b).io
    h.record(0) := sampledCycle
    for (w <- 0 until 7) h.record(w+1) := sampled(b).words(w)
    h.record(1) := Cat(io.privilege, sampled(b).words(0)(61,0))
    h.write := sampled(b).valid
    h.capture.valid := capture; h.capture.bits := captureSlot
    h.extend := extending
    h.clear := clear
    h.read := readFire && bank === b.U
    h.readSlot := leaseSlot; h.readEntry := entry
    // Pipeline the wide BRAM/word/bank selection; JTAG latency is immaterial.
    when(readPending) { wordData(b) := h.data(readWord) }
  }
  readPending := readFire
  when(replyPending) { replyValid := true.B; replyError := false.B; replyData := wordData(readBank) }
  for (s <- 0 until snapshots) {
    when(extending(s)) { remaining(s) := remaining(s) - 1.U }
  }
  when(capture) {
    valid(captureSlot) := true.B
    remaining(captureSlot) := postCycles.U
    ids(captureSlot) := captures + 1.U
    manual(captureSlot) := !fault
    headers(captureSlot)(0) := sampledCycle
    for (w <- 0 until 7) headers(captureSlot)(w+1) := Mux(fault, sampled(0).words(w), 0.U)
    headers(captureSlot)(1) := Mux(fault, Cat(io.privilege, sampled(0).words(0)(61,0)), 0.U)
    captures := captures + 1.U
    latestCycle := sampledCycle
    nextSlot := captureSlot + 1.U
    when(valid(captureSlot)) { evicted := evicted + 1.U }
  }
  when(fire) {
    replyValid := !readFire; replyError := false.B; replyData := 0.U
    switch(op) {
      is(16.U) {
        replyData := Cat(2.U(32.W), depth.U(16.W), banks.U(8.W), 0.U(7.W), true.B)
        when(index >= 1.U && index <= banks.U) {
          replyData := VecInit(history.map(_.io.total))((index-1.U)(2,0))
        }.elsewhen(index === 7.U) { replyData := captures }
          .elsewhen(index === 8.U) { replyData := evicted }
          .elsewhen(index === 9.U) { replyData := latestCycle }
          .elsewhen(index === 10.U) { replyData := Mux(leaseValid, ids(leaseSlot), 0.U) }
          .elsewhen(index === 11.U) { replyData := snapshots.U }
          .elsewhen(index === 12.U) { replyData := preEntries.U }
          .elsewhen(index === 13.U) { replyData := postCycles.U }
          .elsewhen(index === 14.U) { replyData := VecInit(history.map(_.io.dropped =/= 0.U)).asUInt }
          .elsewhen(index === 15.U) { replyData := 2.U }
          .elsewhen(index >= 16.U && index < (16+banks).U) {
            replyData := VecInit(history.map(_.io.dropped))((index-16.U)(2,0))
          }.elsewhen(index =/= 0.U) { replyError := true.B }
      }
      is(17.U) {
        val slot = arg(sw-1,0)
        when(arg >= snapshots.U) { replyError := true.B }
        .otherwise {
          when(index === 0.U) { replyData := Cat(manual(slot), leaseValid && leaseSlot === slot,
              valid(slot) && remaining(slot) === 0.U, valid(slot)) }
          .elsewhen(index === 1.U) { replyData := ids(slot) }
          .elsewhen(index >= 2.U && index < 10.U) { replyData := headers(slot)((index-2.U)(2,0)) }
          .elsewhen(index >= 10.U && index < 16.U) {
            val counts = VecInit(history.map(h => (h.io.counts(slot) << 16) | h.io.preCounts(slot)))
            replyData := counts((index-10.U)(2,0))
          }.otherwise { replyError := true.B }
        }
      }
      is(18.U) {
        replyError := !selectOK
        when(selectOK) { leaseValid := true.B; leaseSlot := selectedSlot; replyData := headers(selectedSlot)(0) }
      }
      is(19.U) { replyError := !readOK }
      is(20.U) { leaseValid := false.B }
      is(21.U) { leaseValid := true.B; leaseSlot := captureSlot; replyData := captures + 1.U }
      is(23.U) {
        valid.foreach(_ := false.B); remaining.foreach(_ := 0.U); ids.foreach(_ := 0.U)
        manual.foreach(_ := false.B); headers.foreach(_.foreach(_ := 0.U))
        nextSlot := 0.U; captures := 0.U; evicted := 0.U; latestCycle := 0.U; leaseValid := false.B
      }
    }
    when(op < 16.U || op > 23.U || op === 22.U) { replyError := true.B }
  }
  io.probes(0) := sampledCycle
  io.probes(1) := Cat(0.U(53.W), VecInit(sampled.map(_.valid)).asUInt, capture, captures =/= 0.U, false.B, true.B, clear)
  io.probes(2) := sampled(1).words(2)
  io.probes(3) := sampled(4).words(2)
  io.probes(4) := sampled(5).words(1)
  io.probes(5) := sampled(5).words(2)
  io.probes(6) := sampled(0).words(1)
  io.probes(7) := sampled(5).words(0)
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
