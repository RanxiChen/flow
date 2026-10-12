package flow.rvv.zve

import chisel3._
import chisel3.util._
import flow.rvv.RvvAxi

/** Independent address/translation producer and ordered memory consumer.
  * Three chunks may be translated ahead of the memory head. Only that head
  * acquires a PA lease or touches AXI/VRF; load fields publish atomically after
  * their final chunk. Valid/ownership bits advance on their own handshakes. */
class ZveMemoryEngine(p: ZveParams) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ZveCommand(p)))
    val vrf = new ZveVrfPort(p); val mask = Input(UInt(p.vrf.vlen.W))
    val translation = Decoupled(new ZveTranslationRequest(p))
    val translated = Flipped(Decoupled(new ZveTranslationResponse(p)))
    val order = Decoupled(new ZveMemoryOrder); val release = Decoupled(UInt(16.W))
    val invalidate = Decoupled(UInt(64.W)); val scalarWritesVisible = Input(Bool())
    val axi = new RvvAxi(p.vrf)
    val complete = Decoupled(new ZveCompletion(p)); val drained = Output(Bool())
    val scalarMemoryBlocked = Output(Bool())
  })
  val InFlight = 3
  class IftEntry extends Bundle {
    val valid = Bool(); val awaiting = Bool(); val tag = UInt(16.W)
    val va = UInt(64.W); val chunkLog = UInt(2.W); val store = Bool()
    // Producer position is private to this transaction, including fault/restart.
    val element = UInt((p.vrf.lengthBits + 1).W); val field = UInt(3.W)
    val chunk = UInt(3.W); val lastChunk = Bool()
    val row = UInt(p.vrf.rowBits.W); val offset = UInt(log2Ceil(p.vrf.rowBytes).W)
    val storeData = UInt(32.W)
    val translated = Bool(); val pa = UInt(64.W); val fault = Bool()
    val faultCause = UInt(64.W); val faultTval = UInt(64.W); val device = Bool()
  }
  val ift = RegInit(VecInit(Seq.fill(InFlight)(0.U.asTypeOf(new IftEntry))))
  val iftHead = RegInit(0.U(log2Ceil(InFlight).W))
  val iftTail = RegInit(0.U(log2Ceil(InFlight).W))
  def nextSlot(slot: UInt): UInt = Mux(slot === (InFlight - 1).U, 0.U, slot + 1.U)
  val inFlightCount = RegInit(0.U(log2Ceil(InFlight + 1).W))
  val active = RegInit(false.B); val d = Reg(new ZveCommand(p)); val mask = Reg(UInt(p.vrf.vlen.W))
  val nextTag = RegInit(0.U(16.W))
  val genElement = Reg(UInt((p.vrf.lengthBits + 1).W)); val genField = Reg(UInt(3.W)); val genChunk = Reg(UInt(3.W))
  val sourceReady = RegInit(false.B); val sourceOutstanding = RegInit(false.B)
  val sourceTag = Reg(UInt(16.W)); val index = Reg(UInt(64.W)); val storeData = Reg(UInt(32.W))
  val assembled = RegInit(0.U(32.W))
  val stop = RegInit(false.B); val exception = RegInit(false.B)
  val cause = Reg(UInt(64.W)); val tval = Reg(UInt(64.W)); val faultElement = Reg(UInt((p.vrf.lengthBits + 1).W))
  val shorten = RegInit(false.B); val shortenedVl = Reg(UInt(p.vrf.lengthBits.W))
  // One ordered lease/AXI transaction. These bits own responses independently
  // of the producer, rather than sending the entire engine through an FSM.
  val leaseActive = RegInit(false.B); val invalidationDone = RegInit(false.B)
  val readPending = RegInit(false.B); val awSent = RegInit(false.B); val wSent = RegInit(false.B)
  val writeResponsePending = RegInit(false.B)
  val publishPending = RegInit(false.B); val publishOutstanding = RegInit(false.B)
  val releasePending = RegInit(false.B)

  val x = d.issue.scalar.instruction; val store = x(6, 0) === 0x27.U
  val mop = x(27, 26); val indexed = mop(0); val strided = mop === 2.U
  val whole = d.whole; val maskMemory = mop === 0.U && x(24, 20) === 11.U
  val faultOnlyFirst = !store && mop === 0.U && x(24, 20) === 16.U
  val width = Mux(maskMemory, 0.U, d.destinationWidth)
  val dataBytes = 1.U(3.W) << width
  val fields = Mux(whole || maskMemory, 1.U, x(31, 29) +& 1.U)
  val limit = Mux(whole, ((x(31, 29) +& 1.U) * p.vrf.regBytes.U) >> width,
    Mux(maskMemory, (d.issue.scalar.vl +& 7.U) >> 3, d.issue.scalar.vl))
  val destinationByte = Mux(whole || maskMemory, genElement << width,
    genField * d.destinationRegisters * p.vrf.regBytes.U + (genElement << width))
  val destinationRow = (x(11, 7) * p.vrf.rowsPerReg.U + (destinationByte >> log2Ceil(p.vrf.rowBytes)))(p.vrf.rowBits - 1, 0)
  val destinationOffset = destinationByte(log2Ceil(p.vrf.rowBytes) - 1, 0)
  val indexByte = genElement << d.source2Width
  val indexRow = (x(24, 20) * p.vrf.rowsPerReg.U + (indexByte >> log2Ceil(p.vrf.rowBytes)))(p.vrf.rowBits - 1, 0)
  val position = Mux(whole || maskMemory, genElement * dataBytes,
    Mux(indexed, index, Mux(strided, genElement * d.issue.scalar.rs2, genElement * fields * dataBytes))) + genField * dataBytes
  val base = d.issue.scalar.rs1 + position
  val genChunkLog = Mux((base & (dataBytes - 1.U)) === 0.U, width, 0.U)
  val genLastChunk = genChunk +& (1.U << genChunkLog) >= dataBytes
  val generationDone = genElement >= limit
  val masked = !whole && !maskMemory && !x(25) && !(mask >> genElement)(0)
  val head = ift(iftHead)
  val headReady = head.valid && head.translated
  val translationFault = active && !stop && !leaseActive && headReady && head.fault
  val readFault = io.axi.r.fire && io.axi.r.bits.resp =/= 0.U
  val writeFault = io.axi.b.fire && io.axi.b.bits.resp =/= 0.U
  val cancelNow = translationFault || readFault || writeFault
  val producerRunning = active && !stop && !cancelNow && !generationDone

  io.in.ready := !active
  when(io.in.fire) {
    active := true.B; d := io.in.bits; mask := io.mask
    genElement := io.in.bits.issue.scalar.vstart; genField := 0.U; genChunk := 0.U
    sourceReady := false.B; index := 0.U; storeData := 0.U; assembled := 0.U
    stop := false.B; exception := false.B; shorten := false.B; cause := 0.U; tval := 0.U
    shortenedVl := io.in.bits.issue.scalar.vl; faultElement := 0.U
    iftHead := 0.U; iftTail := 0.U
    assert(inFlightCount === 0.U && !sourceOutstanding && !leaseActive, "new memory command before drain")
  }
  when(producerRunning && masked) {
    genElement := genElement + 1.U
  }
  // Indexed/store commands obtain the source once per field; unaligned byte
  // chunks reuse it. Non-indexed loads need no source stage and can issue at II=1.
  io.vrf.read.valid := producerRunning && !masked && (indexed || store) && !sourceReady && !sourceOutstanding
  io.vrf.read.bits.tag := nextTag; io.vrf.read.bits.age := d.age
  io.vrf.read.bits.rows(0) := indexRow; io.vrf.read.bits.rows(1) := destinationRow; io.vrf.read.bits.rows(2) := 0.U
  io.vrf.read.bits.enables := Cat(false.B, store, indexed)
  when(io.vrf.read.fire) { sourceOutstanding := true.B; sourceTag := nextTag }
  io.vrf.returned.ready := sourceOutstanding
  when(io.vrf.returned.fire) {
    assert(io.vrf.returned.bits.tag === sourceTag, "memory VRF return has the wrong owner")
    sourceOutstanding := false.B
    when(!stop && !cancelNow) {
      val raw = (io.vrf.returned.bits.data(0) >> (indexByte(log2Ceil(p.vrf.rowBytes) - 1, 0) << 3))(31, 0)
      index := MuxLookup(d.source2Width, raw)(Seq(0.U -> raw(7, 0).pad(32), 1.U -> raw(15, 0).pad(32)))
      storeData := (io.vrf.returned.bits.data(1) >> (destinationOffset << 3))(31, 0)
      sourceReady := true.B
    }
  }
  val slotAvailable = !ift(iftTail).valid && !ift(iftTail).awaiting
  io.translation.valid := producerRunning && !masked && (!(indexed || store) || sourceReady) && slotAvailable
  io.translation.bits.tag := nextTag; io.translation.bits.va := base + genChunk
  io.translation.bits.bytes := 1.U << genChunkLog; io.translation.bits.store := store
  io.translation.bits.epoch := d.issue.epoch
  when(io.translation.fire) {
    val e = ift(iftTail)
    e.valid := true.B; e.awaiting := true.B; e.tag := nextTag
    e.va := base + genChunk; e.chunkLog := genChunkLog; e.store := store
    e.element := genElement; e.field := genField; e.chunk := genChunk; e.lastChunk := genLastChunk
    e.row := destinationRow; e.offset := destinationOffset; e.storeData := storeData
    e.translated := false.B; e.fault := false.B; e.device := false.B
    iftTail := nextSlot(iftTail); nextTag := nextTag + 1.U
    when(genLastChunk) {
      genChunk := 0.U; sourceReady := false.B
      when(genField +& 1.U < fields) { genField := genField + 1.U }
        .otherwise { genField := 0.U; genElement := genElement + 1.U }
    }.otherwise { genChunk := genChunk + (1.U << genChunkLog) }
  }

  // Always accept responses, including killed younger entries. Awaiting retains
  // their tag ownership after valid is cleared. A same-cycle response to a new
  // request is bypassed to its allocation slot as well.
  io.translated.ready := true.B
  val responseMatches = VecInit(ift.map(e => e.awaiting && e.tag === io.translated.bits.tag))
  val allocationResponse = io.translation.fire && nextTag === io.translated.bits.tag
  when(io.translated.fire) {
    assert(active && io.translated.bits.epoch === d.issue.epoch, "translation lost epoch/command")
    assert(PopCount(responseMatches) +& allocationResponse.asUInt === 1.U, "translated tag not found or duplicated in IFT")
    for (i <- 0 until InFlight) {
      val allocatedHere = allocationResponse && iftTail === i.U
      when(responseMatches(i) || allocatedHere) {
        val e = ift(i)
        e.awaiting := false.B
        when(!stop && !cancelNow) {
          val bytes = Mux(allocatedHere, io.translation.bits.bytes, 1.U << e.chunkLog)
          val va = Mux(allocatedHere, io.translation.bits.va, e.va)
          val isStore = Mux(allocatedHere, io.translation.bits.store, e.store)
          val bad = io.translated.bits.exception || io.translated.bits.device
          e.translated := true.B; e.pa := io.translated.bits.pa; e.fault := bad; e.device := io.translated.bits.device
          e.faultCause := Mux(io.translated.bits.exception, io.translated.bits.cause, Mux(isStore, 7.U, 5.U))
          e.faultTval := Mux(io.translated.bits.exception, io.translated.bits.tval, va)
          when(!bad) {
            assert(io.translated.bits.bytes >= bytes, "translation did not authorize the entire chunk")
            assert((io.translated.bits.pa >> p.vrf.paBits) === 0.U, "translated PA exceeds platform width")
          }
        }
      }
    }
  }
  when(io.translation.fire =/= io.translated.fire) {
    inFlightCount := Mux(io.translation.fire, inFlightCount + 1.U, inFlightCount - 1.U)
  }
  when(io.translated.fire) { assert(inFlightCount =/= 0.U || io.translation.fire, "translation count underflow") }
  assert(inFlightCount <= InFlight.U, "translation count overflow")

  // The head stays allocated through lease release. Young translation responses
  // cannot reorder memory requests, stores, invalidations or physical writes.
  io.order.valid := active && !stop && headReady && !head.fault && !leaseActive && io.scalarWritesVisible
  io.order.bits.tag := head.tag; io.order.bits.age := d.issue.memoryAge
  io.order.bits.pa := head.pa; io.order.bits.bytes := 1.U << head.chunkLog; io.order.bits.store := head.store
  when(io.order.fire) {
    leaseActive := true.B; invalidationDone := !head.store
    awSent := false.B; wSent := false.B
  }
  io.invalidate.valid := leaseActive && head.store && !invalidationDone && !releasePending
  io.invalidate.bits := head.pa & (~(p.vrf.cacheLineBytes - 1).U(64.W))
  when(io.invalidate.fire) { invalidationDone := true.B }
  io.axi.ar.valid := leaseActive && !head.store && !readPending &&
    !publishPending && !publishOutstanding && !releasePending
  io.axi.ar.bits := 0.U.asTypeOf(io.axi.ar.bits)
  io.axi.ar.bits.addr := head.pa; io.axi.ar.bits.size := head.chunkLog; io.axi.ar.bits.burst := 1.U
  when(io.axi.ar.fire) { readPending := true.B }
  io.axi.r.ready := readPending
  val chunkMask = MuxLookup(head.chunkLog, "hffffffff".U(32.W))(Seq(0.U -> "hff".U(32.W), 1.U -> "hffff".U(32.W)))
  val busOffset = head.pa(log2Ceil(p.vrf.memBytes) - 1, 0)
  when(io.axi.r.fire) {
    assert(io.axi.r.bits.id === 0.U && io.axi.r.bits.last, "memory read framing error")
    readPending := false.B
    when(!readFault) {
      val data = (io.axi.r.bits.data >> (busOffset << 3))(31, 0) & chunkMask
      val shiftedMask = (chunkMask << (head.chunk << 3))(31, 0)
      assembled := (assembled & ~shiftedMask) | ((data << (head.chunk << 3))(31, 0))
      when(head.lastChunk) { publishPending := true.B }.otherwise { releasePending := true.B }
    }
  }
  io.axi.aw.valid := leaseActive && head.store && invalidationDone && !awSent && !releasePending
  io.axi.aw.bits := io.axi.ar.bits
  io.axi.w.valid := leaseActive && head.store && invalidationDone && !wSent && !releasePending
  io.axi.w.bits := 0.U.asTypeOf(io.axi.w.bits)
  io.axi.w.bits.data := (((head.storeData >> (head.chunk << 3)) & chunkMask).pad(p.vrf.memoryBits) << (busOffset << 3))(p.vrf.memoryBits - 1, 0)
  io.axi.w.bits.strb := ((((1.U((p.vrf.memBytes + 1).W) << (1.U << head.chunkLog)) - 1.U) << busOffset))(p.vrf.memBytes - 1, 0)
  io.axi.w.bits.last := true.B
  when(io.axi.aw.fire) { awSent := true.B }
  when(io.axi.w.fire) { wSent := true.B }
  when(leaseActive && head.store && !releasePending && !writeResponsePending &&
      (awSent || io.axi.aw.fire) && (wSent || io.axi.w.fire)) { writeResponsePending := true.B }
  io.axi.b.ready := writeResponsePending
  when(io.axi.b.fire) {
    assert(io.axi.b.bits.id === 0.U, "memory write response has the wrong AXI ID")
    writeResponsePending := false.B; releasePending := true.B
  }
  io.vrf.write.valid := publishPending
  io.vrf.write.bits.tag := head.tag; io.vrf.write.bits.age := d.age; io.vrf.write.bits.row := head.row
  io.vrf.write.bits.data := (assembled.pad(p.vrf.dlen) << (head.offset << 3))(p.vrf.dlen - 1, 0)
  io.vrf.write.bits.enables := ((((1.U((p.vrf.rowBytes + 1).W) << dataBytes) - 1.U) << head.offset))(p.vrf.rowBytes - 1, 0)
  when(io.vrf.write.fire) { publishPending := false.B; publishOutstanding := true.B }
  io.vrf.written.ready := publishOutstanding
  when(io.vrf.written.fire) {
    assert(io.vrf.written.bits.tag === head.tag, "memory publish ack has the wrong owner")
    publishOutstanding := false.B; releasePending := true.B
  }
  io.release.valid := releasePending; io.release.bits := head.tag
  when(io.release.fire) {
    assert(leaseActive, "release without lease")
    releasePending := false.B; leaseActive := false.B
    awSent := false.B; wSent := false.B
    when(!stop) {
      head.valid := false.B; iftHead := nextSlot(iftHead)
      when(head.lastChunk) { assembled := 0.U }
    }
  }
  when(cancelNow) {
    stop := true.B; sourceReady := false.B; faultElement := head.element
    cause := Mux(translationFault, head.faultCause, Mux(head.store, 7.U, 5.U))
    tval := Mux(translationFault, head.faultTval, head.va)
    when(faultOnlyFirst && head.element =/= 0.U) {
      shorten := true.B; shortenedVl := head.element; exception := false.B
    }.otherwise { exception := true.B }
    // Preserve awaiting/tag until each external translation responds. If AXI
    // failed, keep the active lease's payload at the head until release.fire.
    for (e <- ift) { e.valid := false.B }
    when(leaseActive) { releasePending := true.B }
  }
  val iftEmpty = !ift.map(_.valid).reduce(_ || _)
  io.complete.valid := active && (stop || generationDone) && iftEmpty && inFlightCount === 0.U &&
    !sourceOutstanding && !leaseActive && !publishPending && !publishOutstanding && !releasePending
  io.complete.bits := 0.U.asTypeOf(new ZveCompletion(p))
  io.complete.bits.slot := d.slot; io.complete.bits.age := d.age
  io.complete.bits.exception := exception; io.complete.bits.cause := cause; io.complete.bits.tval := tval
  io.complete.bits.vstart := Mux(exception, faultElement, 0.U)
  io.complete.bits.changeVl := shorten; io.complete.bits.newVl := shortenedVl
  when(io.complete.fire) { active := false.B }
  io.drained := !active
  io.scalarMemoryBlocked := active
  when(leaseActive && !stop) { assert(headReady && !head.fault, "lease lost its ordered head") }
}
