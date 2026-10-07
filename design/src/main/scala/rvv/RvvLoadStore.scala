package flow.rvv

import chisel3._
import chisel3.util._

/** Single-ID, ordered AXI bursts; requests, receipt and VRF consumption have
  * separate lifetimes. Outstanding credit includes all reserved return beats. */
class RvvLoadStore(p: RvvParams) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new RvvDescriptor(p)))
    val prefetch = Flipped(Decoupled(new RvvDescriptor(p)))
    val nextAge = Input(UInt(p.ageBits.W)); val ageAllowed = Output(Bool())
    val prefetchEvent = Valid(UInt(p.ageBits.W))
    val axi = new RvvAxi(p)
    val scalarWritesVisible = Input(Bool())
    val ordering = Output(new RvvDescriptor(p)); val orderingValid = Output(Bool()); val conflict = Input(Bool())
    val released = Valid(UInt(64.W))
    val invalidate = Decoupled(UInt(64.W))
    val storeReadValid = Output(Bool()); val storeRow = Output(UInt(p.rowBits.W)); val storeData = Input(UInt(p.dlen.W))
    val write = Decoupled(new RvvWrite(p)); val writeAge = Output(UInt(p.ageBits.W))
    val hazard = Output(Vec(2,new RvvHazard(p))); val blocked = Input(Vec(2,Bool()))
    val progress = Vec(2,Valid(new RvvProgress(p)))
    val writeWar = Input(Bool())
    val busy = Output(Bool())
    val readBytes = Output(UInt(64.W)); val writeBytes = Output(UInt(64.W)); val bufferedBytes = Output(UInt(64.W))
    val war = Output(UInt(64.W)); val overlap = Output(UInt(64.W)); val credit = Output(UInt(64.W)); val axiStall = Output(UInt(64.W))
    val invalidations = Output(UInt(64.W))
  })
  val desc = Reg(Vec(p.memoryInflight,new RvvDescriptor(p)))
  val bound = RegInit(VecInit(Seq.fill(p.memoryInflight)(false.B)))
  val live = RegInit(VecInit(Seq.fill(p.memoryInflight)(false.B)))
  val intervalLive = RegInit(VecInit(Seq.fill(p.memoryInflight)(false.B)))
  val requestDone = RegInit(VecInit(Seq.fill(p.memoryInflight)(false.B)))
  val dataDone = RegInit(VecInit(Seq.fill(p.memoryInflight)(false.B)))
  val responses = RegInit(VecInit(Seq.fill(p.memoryInflight)(0.U(log2Ceil(p.returnBeats+1).W))))
  val current = Reg(UInt(p.memorySlotBits.W)); val generating = RegInit(false.B)
  val position = RegInit(0.U(p.lengthBits.W)); val firstRequest = RegInit(true.B)
  val scalarVisible = RegInit(false.B)
  val invalidatePosition = RegInit(0.U(p.lengthBits.W)); val invalidateDone = RegInit(true.B)
  val free = PriorityEncoder(~live.asUInt)
  // Request stream is a separate committed VIQ cursor, including stores.
  // Allocation never changes the dispatch cursor or scoreboard ownership.
  val binding = VecInit((0 until p.memoryInflight).map(k => live(k) && !bound(k) && desc(k).age === io.in.bits.age))
  val bindingIndex = PriorityEncoder(binding.asUInt)
  io.in.ready := binding.asUInt.orR
  when(io.in.fire) {
    assert(PopCount(binding) === 1.U,"prefetch binding must be unique across age wrap")
    bound(bindingIndex) := true.B; desc(bindingIndex).slot := io.in.bits.slot
  }
  io.ageAllowed := (0 until p.memoryInflight).map(k => !live(k) || (io.nextAge-desc(k).age) < p.ageLimit.U).reduce(_ && _)
  io.prefetch.ready := !io.prefetch.bits.decoded.memory || (!generating && !live.asUInt.andR)
  io.prefetchEvent.valid := io.prefetch.fire && io.prefetch.bits.decoded.memory && !io.prefetch.bits.decoded.store
  io.prefetchEvent.bits := io.prefetch.bits.age
  when(io.prefetch.fire && io.prefetch.bits.decoded.memory) {
    current := free; desc(free) := io.prefetch.bits; live(free) := true.B; bound(free) := false.B
    intervalLive(free) := io.prefetch.bits.decoded.bytes =/= 0.U
    requestDone(free) := false.B; responses(free) := 0.U; dataDone(free) := false.B
    generating := true.B; position := 0.U; firstRequest := true.B; scalarVisible := false.B
    invalidatePosition := 0.U
    invalidateDone := !io.prefetch.bits.decoded.store || io.prefetch.bits.decoded.bytes === 0.U
  }
  val d = desc(current)
  val noAccess = d.issue.vl === 0.U || d.issue.vstart >= d.issue.vl
  val segment = position >= d.length(0)
  val segmentOffset = Mux(segment,position-d.length(0),position)
  val segmentRemain = d.length(segment)-segmentOffset
  val pa = d.pa(segment)+segmentOffset
  val address = pa & (~(p.memBytes-1).U(64.W))
  val prefix = pa(log2Ceil(p.memBytes)-1,0)
  val neededBeats = (segmentRemain +& prefix + (p.memBytes-1).U) >> log2Ceil(p.memBytes)
  val pageBeats = (4096.U - address(11,0)) >> log2Ceil(p.memBytes)
  val nBeats = Mux(neededBeats < p.burstBeats.U,neededBeats,p.burstBeats.U)
  val beats = Mux(nBeats < pageBeats,nBeats,pageBeats)
  val covered = beats * p.memBytes.U - prefix
  val consumed = Mux(covered < segmentRemain,covered,segmentRemain)
  val finalBurst = position + consumed >= d.decoded.bytes
  val logical = position.zext - prefix.zext
  io.ordering := d; io.orderingValid := generating && !noAccess

  class Burst extends Bundle {
    val slot = UInt(p.memorySlotBits.W); val logical = SInt((p.lengthBits+2).W)
    val beats = UInt(9.W); val finalInstruction = Bool()
  }
  class ReturnBeat extends Bundle {
    val data = UInt(p.memoryBits.W); val slot = UInt(p.memorySlotBits.W)
    val logical = SInt((p.lengthBits+2).W); val finalInstruction = Bool()
  }
  val bursts = Module(new Queue(new Burst,p.returnBeats))
  val returnBuffer = Module(new Queue(new ReturnBeat,p.returnBeats,useSyncReadMem=true))
  val bSlots = Module(new Queue(UInt(p.memorySlotBits.W),p.memoryInflight))
  val reserved = RegInit(0.U(log2Ceil(p.returnBeats+1).W))
  when(generating && io.scalarWritesVisible) { scalarVisible := true.B }
  val canRequest = generating && !noAccess && !io.conflict && (!firstRequest || scalarVisible || io.scalarWritesVisible)
  val creditOk = reserved +& beats <= p.returnBeats.U
  val writeBurst = RegInit(false.B); val writeMeta = Reg(new Burst)
  val writeBeat = RegInit(0.U(9.W)); val gathered = RegInit(false.B)
  val gatheredData = RegInit(0.U(p.memoryBits.W)); val gatherRow = Reg(UInt(p.rowBits.W))
  val gathering = RegInit(false.B); val storePending = RegInit(false.B)

  io.axi.ar.valid := canRequest && !d.decoded.store && creditOk && bursts.io.enq.ready
  io.axi.ar.bits := 0.U.asTypeOf(new RvvAxiAddress(p))
  io.axi.ar.bits.addr := address; io.axi.ar.bits.len := beats-1.U
  io.axi.ar.bits.size := log2Ceil(p.memBytes).U; io.axi.ar.bits.burst := 1.U
  bursts.io.enq.valid := io.axi.ar.fire
  bursts.io.enq.bits.slot := current; bursts.io.enq.bits.logical := logical
  bursts.io.enq.bits.beats := beats; bursts.io.enq.bits.finalInstruction := finalBurst
  io.axi.aw.valid := canRequest && d.decoded.store && bound(current) && !writeBurst && invalidateDone && bSlots.io.enq.ready
  io.axi.aw.bits := io.axi.ar.bits
  bSlots.io.enq.valid := io.axi.aw.fire; bSlots.io.enq.bits := current
  val requestFire = io.axi.ar.fire || io.axi.aw.fire
  when(requestFire) {
    position := position+consumed; firstRequest := false.B
    when(finalBurst && !d.decoded.store) { generating := false.B; requestDone(current) := true.B }
  }
  when(io.axi.aw.fire) {
    writeBurst := true.B; writeBeat := 0.U
    writeMeta := bursts.io.enq.bits; gathered := false.B; gathering := false.B
  }

  // Drain R unconditionally. Metadata follows the single AXI ID, never the
  // newest instruction. Credits are returned only when raw data is consumed.
  val readBeat = RegInit(0.U(9.W))
  io.axi.r.ready := true.B
  val rb = bursts.io.deq.bits
  returnBuffer.io.enq.valid := io.axi.r.valid
  returnBuffer.io.enq.bits.data := io.axi.r.bits.data
  returnBuffer.io.enq.bits.slot := rb.slot
  returnBuffer.io.enq.bits.logical := rb.logical + (readBeat*p.memBytes.U).zext
  returnBuffer.io.enq.bits.finalInstruction := rb.finalInstruction && io.axi.r.bits.last
  bursts.io.deq.ready := io.axi.r.fire && io.axi.r.bits.last
  when(io.axi.r.fire) {
    assert(bursts.io.deq.valid && returnBuffer.io.enq.ready,"return data exceeds reserved credit")
    assert(io.axi.r.bits.id === 0.U && io.axi.r.bits.resp === 0.U,"AXI load failure after Ok")
    assert(io.axi.r.bits.last === (readBeat+1.U === rb.beats),"R last does not match AR length")
    readBeat := Mux(io.axi.r.bits.last,0.U,readBeat+1.U)
  }
  io.axi.b.ready := true.B; bSlots.io.deq.ready := io.axi.b.fire
  when(io.axi.b.fire) { assert(bSlots.io.deq.valid && io.axi.b.bits.id === 0.U && io.axi.b.bits.resp === 0.U) }
  for(k <- 0 until p.memoryInflight) {
    val additions = Mux(io.axi.ar.fire && current === k.U,beats,0.U) +
      Mux(io.axi.aw.fire && current === k.U,1.U,0.U)
    val subtractions = (io.axi.r.fire && rb.slot === k.U).asUInt +&
      (io.axi.b.fire && bSlots.io.deq.bits === k.U).asUInt
    when(additions =/= 0.U || subtractions =/= 0.U) {
      assert(responses(k)+&additions >= subtractions,"response accounting underflow")
      responses(k) := responses(k)+additions-subtractions
    }
  }
  val releaseMask = VecInit((0 until p.memoryInflight).map(k =>
    live(k) && intervalLive(k) && requestDone(k) && responses(k) === 0.U))
  val releaseIndex = PriorityEncoder(releaseMask.asUInt)
  io.released.valid := releaseMask.asUInt.orR && !(desc(releaseIndex).decoded.store && gathering) &&
    !(generating && noAccess)
  io.released.bits := desc(releaseIndex).age
  when(io.released.valid) {
    intervalLive(releaseIndex) := false.B
    when(dataDone(releaseIndex) || (desc(releaseIndex).decoded.store && bound(releaseIndex))) { live(releaseIndex) := false.B }
  }
  for(k <- 0 until p.memoryInflight) {
    when(live(k) && dataDone(k) && !intervalLive(k)) { live(k) := false.B }
  }

  // Invalidate each touched physical cache line, including discontinuous pages.
  val invSeg = invalidatePosition >= d.length(0)
  val invOffset = Mux(invSeg,invalidatePosition-d.length(0),invalidatePosition)
  val invPa = d.pa(invSeg)+invOffset
  val invRemaining = d.length(invSeg)-invOffset
  val lineRemaining = p.cacheLineBytes.U - invPa(log2Ceil(p.cacheLineBytes)-1,0)
  val invAdvance = Mux(invRemaining < lineRemaining,invRemaining,lineRemaining)
  io.invalidate.valid := generating && d.decoded.store && bound(current) && !invalidateDone && !noAccess
  io.invalidate.bits := invPa & (~(p.cacheLineBytes-1).U(64.W))
  when(io.invalidate.fire) {
    invalidatePosition := invalidatePosition+invAdvance
    when(invalidatePosition+invAdvance >= d.decoded.bytes) { invalidateDone := true.B }
  }

  io.progress.foreach { x => x.valid := false.B; x.bits := 0.U.asTypeOf(new RvvProgress(p)) }
  io.hazard.foreach(_ := 0.U.asTypeOf(new RvvHazard(p)))
  when(generating && noAccess) { generating := false.B; requestDone(current) := true.B }
  val zeroMask = VecInit((0 until p.memoryInflight).map(k => live(k) && bound(k) && requestDone(k) &&
    (desc(k).issue.vl === 0.U || desc(k).issue.vstart >= desc(k).issue.vl)))
  val zeroIndex = PriorityEncoder(zeroMask.asUInt)
  val retiringStore = io.released.valid && desc(releaseIndex).decoded.store &&
    desc(releaseIndex).issue.vl =/= 0.U && desc(releaseIndex).issue.vstart < desc(releaseIndex).issue.vl
  when(zeroMask.asUInt.orR && !gathering && !retiringStore) {
    io.progress(0).valid := true.B; io.progress(0).bits.slot := desc(zeroIndex).slot
    io.progress(0).bits.finished := true.B; dataDone(zeroIndex) := true.B; live(zeroIndex) := false.B
  }
  // Store gathering uses only its dedicated read port, one memory-order row at
  // a time. It holds a complete W beat stable through arbitrary W backpressure.
  val wd = desc(writeMeta.slot)
  val wLogical = writeMeta.logical + (writeBeat*p.memBytes.U).zext
  val wFirst = Mux(wLogical < 0.S,0.U,wLogical.asUInt)
  val wEndRaw = wLogical + p.memBytes.S
  val wEnd = Mux(wEndRaw > wd.decoded.bytes.zext,wd.decoded.bytes,wEndRaw.asUInt)
  val firstRow = wFirst >> log2Ceil(p.rowBytes)
  val lastRow = (wEnd-1.U) >> log2Ceil(p.rowBytes)
  when(writeBurst && !gathered && !gathering) {
    gatherRow := firstRow; gatheredData := 0.U; gathering := true.B
  }
  val storeReturned = RegNext(RegNext(io.storeReadValid,false.B),false.B)
  val storeReg = wd.decoded.vd + (gatherRow >> log2Ceil(p.rowsPerReg))
  io.storeRow := wd.decoded.vd*p.rowsPerReg.U+gatherRow
  io.hazard(0).valid := gathering
  io.hazard(0).slot := wd.slot; io.hazard(0).reads := (1.U(32.W) << storeReg)(31,0)
  val wStrb = VecInit((0 until p.memBytes).map { b =>
    val offset = wLogical+b.S
    offset >= 0.S && offset < wd.decoded.bytes.zext
  }).asUInt
  def rotateBytesRight(data: UInt, bytes: Int, amount: UInt): UInt = {
    val double = Cat(data,data)
    (double >> (amount << 3))(bytes*8-1,0)
  }
  val storeRotation = (wLogical.asUInt-gatherRow*p.rowBytes.U)(log2Ceil(p.rowBytes)-1,0)
  val storeRotated = rotateBytesRight(io.storeData,p.rowBytes,storeRotation)
  val gatheredBytes = Wire(Vec(p.memBytes,UInt(8.W)))
  gatheredBytes := gatheredData.asTypeOf(Vec(p.memBytes,UInt(8.W)))
  for(b <- 0 until p.memBytes) {
    val offset = wLogical+b.S
    when(wStrb(b) && (offset.asUInt >> log2Ceil(p.rowBytes)) === gatherRow) {
      gatheredBytes(b) := storeRotated(8*(b%p.rowBytes)+7,8*(b%p.rowBytes))
    }
  }
  io.storeReadValid := gathering && !storePending && !io.blocked(0)
  when(io.storeReadValid) { storePending := true.B }
  when(gathering && storePending && storeReturned && !io.blocked(0)) {
    storePending := false.B
    gatheredData := gatheredBytes.asUInt
    when(gatherRow === lastRow) { gathering := false.B; gathered := true.B }
      .otherwise { gatherRow := gatherRow+1.U }
    val regEnd = ((gatherRow >> log2Ceil(p.rowsPerReg))+1.U)*p.regBytes.U
    val regLast = ((gatherRow+1.U) % p.rowsPerReg.U) === 0.U
    when((regLast && wEnd >= regEnd) || wEnd === wd.decoded.bytes && gatherRow === lastRow) {
      io.progress(0).valid := true.B; io.progress(0).bits.slot := wd.slot
      io.progress(0).bits.readDone := (1.U(32.W) << storeReg)(31,0)
    }
  }
  io.axi.w.valid := writeBurst && gathered
  io.axi.w.bits.data := gatheredData; io.axi.w.bits.strb := wStrb
  io.axi.w.bits.last := writeBeat+1.U === writeMeta.beats
  when(io.axi.w.fire) {
    gathered := false.B; writeBeat := writeBeat+1.U
    when(io.axi.w.bits.last) {
      writeBurst := false.B
      when(writeMeta.finalInstruction) { generating := false.B; requestDone(writeMeta.slot) := true.B }
    }
  }
  // Store's scoreboard entry survives until its B response (and all reads).
  when(io.released.valid && desc(releaseIndex).decoded.store && desc(releaseIndex).issue.vl =/= 0.U &&
    desc(releaseIndex).issue.vstart < desc(releaseIndex).issue.vl) {
    io.progress(0).valid := true.B; io.progress(0).bits.slot := desc(releaseIndex).slot
    io.progress(0).bits.finished := true.B
  }

  // Rotate each buffered bus beat into one or more DLEN rows with byte enables.
  // The data FIFO uses synchronous memory; WAR stalls never reach the R channel.
  val returned = Reg(new ReturnBeat)
  val ld = Reg(new RvvDescriptor(p))
  val returnedValid = RegInit(false.B)
  val writeBound = RegInit(false.B); val writeSlot = Reg(UInt(p.slotBits.W))
  val consumeReturned = Wire(Bool())
  // A registered beat and selected descriptor separate RAM output from hazards.
  returnBuffer.io.deq.ready := !returnedValid || consumeReturned
  when(returnBuffer.io.deq.ready) {
    returnedValid := returnBuffer.io.deq.valid
    writeBound := returnBuffer.io.deq.valid && bound(returnBuffer.io.deq.bits.slot)
    writeSlot := desc(returnBuffer.io.deq.bits.slot).slot
    when(returnBuffer.io.deq.valid) {
      returned := returnBuffer.io.deq.bits; ld := desc(returnBuffer.io.deq.bits.slot)
    }
  }
  val piece = RegInit(0.U(log2Ceil(p.rows+1).W))
  val firstByte = Mux(returned.logical < 0.S,0.U,returned.logical.asUInt)
  val lastByte = Mux(returned.logical+p.memBytes.S > ld.decoded.bytes.zext,
    ld.decoded.bytes,(returned.logical+p.memBytes.S).asUInt)
  val firstVrfRow = firstByte >> log2Ceil(p.rowBytes)
  val lastVrfRow = (lastByte-1.U) >> log2Ceil(p.rowBytes)
  val targetRow = firstVrfRow+piece
  val targetReg = ld.decoded.vd + (targetRow >> log2Ceil(p.rowsPerReg))
  when(returnedValid && !writeBound && !returnBuffer.io.deq.ready) {
    writeBound := bound(returned.slot); writeSlot := desc(returned.slot).slot
  }
  io.hazard(1).valid := returnedValid && writeBound
  io.hazard(1).slot := writeSlot; io.hazard(1).writes := (1.U(32.W) << targetReg)(31,0)
  val outputData = Wire(Vec(p.rowBytes,UInt(8.W))); val outputMask = Wire(Vec(p.rowBytes,Bool()))
  val loadRotation = (targetRow*p.rowBytes.U-returned.logical.asUInt)(log2Ceil(p.memBytes)-1,0)
  val loadRotated = rotateBytesRight(returned.data,p.memBytes,loadRotation)
  for(b <- 0 until p.rowBytes) {
    val logicalByte = targetRow*p.rowBytes.U+b.U
    val sourceByte = logicalByte.zext-returned.logical
    outputMask(b) := logicalByte < ld.decoded.bytes && sourceByte >= 0.S && sourceByte < p.memBytes.S
    outputData(b) := loadRotated(8*(b%p.memBytes)+7,8*(b%p.memBytes))
  }
  io.write.valid := returnedValid && writeBound && !io.blocked(1)
  io.write.bits.row := ld.decoded.vd*p.rowsPerReg.U+targetRow
  io.write.bits.data := outputData.asUInt; io.write.bits.enables := outputMask.asUInt
  io.writeAge := ld.age
  val lastPiece = targetRow === lastVrfRow
  consumeReturned := io.write.fire && lastPiece
  when(io.write.fire) {
    piece := Mux(lastPiece,0.U,piece+1.U)
    val regEnd = ((targetRow >> log2Ceil(p.rowsPerReg))+1.U)*p.regBytes.U
    val registerComplete = (lastByte >= regEnd && ((targetRow+1.U) % p.rowsPerReg.U) === 0.U) ||
      (returned.finalInstruction && lastPiece)
    io.progress(1).valid := true.B; io.progress(1).bits.slot := writeSlot
    io.progress(1).bits.writeDone := Mux(registerComplete,(1.U(32.W) << targetReg)(31,0),0.U)
    io.progress(1).bits.finished := returned.finalInstruction && lastPiece
    when(returned.finalInstruction && lastPiece) { dataDone(returned.slot) := true.B }
  }
  val incomingCredit = Mux(io.axi.ar.fire,beats,0.U)
  val outgoingCredit = consumeReturned.asUInt
  when(io.axi.ar.fire || consumeReturned) {
    reserved := reserved+incomingCredit-outgoingCredit
    assert(reserved+&incomingCredit >= outgoingCredit,"negative return credit")
  }
  assert(reserved <= p.returnBeats.U)
  io.busy := live.asUInt.orR || generating || writeBurst || returnBuffer.io.deq.valid || returnedValid
  def counter(event: Bool, amount: UInt = 1.U): UInt = {
    val x = RegInit(0.U(64.W)); when(event) { x := x+amount }; x
  }
  io.readBytes := counter(io.axi.r.fire,p.memBytes.U)
  io.writeBytes := counter(io.axi.w.fire,PopCount(io.axi.w.bits.strb))
  io.bufferedBytes := (returnBuffer.io.count +& returnedValid.asUInt)*p.memBytes.U
  io.war := counter(returnedValid && io.writeWar)
  io.overlap := counter(generating && io.conflict)
  io.credit := counter(canRequest && !d.decoded.store && !creditOk)
  io.axiStall := counter((io.axi.ar.valid && !io.axi.ar.ready) || (io.axi.aw.valid && !io.axi.aw.ready) || (io.axi.w.valid && !io.axi.w.ready))
  io.invalidations := counter(io.invalidate.fire)
}
