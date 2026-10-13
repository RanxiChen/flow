package flow.rvv.zve

import chisel3._
import chisel3.util._
import flow.fpu.BreezeFpOp

/** Bounded narrow engine for non-hot integer/mask/permute/reduction operations.
  * Snapshots permit the ISA's legal overlaps. Scratch rows are unreset storage;
  * index selection, operand selection, product and result have distinct stages.
  * Other execution units continue while this engine occupies one command. */
class ZveElementEngine(p: ZveParams) extends Module {
  private val maxRows=8*p.vrf.rowsPerReg
  private val scratchBits=log2Ceil(maxRows)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ZveCommand(p)))
    val vrf=new ZveVrfPort(p); val mask=Input(UInt(p.vrf.vlen.W))
    val fp=new ZveFpService
    val complete=Decoupled(new ZveCompletion(p)); val drained=Output(Bool())
  })
  val idle::capture::initialize::select::operands::multiply::execute::divide::fpSend::fpWait::update::finalizeResult::write::finish::Nil=Enum(14)
  val state=RegInit(idle); val d=Reg(new ZveCommand(p)); val mask=Reg(UInt(p.vrf.vlen.W))
  val aRows=Mem(maxRows,UInt(p.vrf.dlen.W)); val bRows=Mem(maxRows,UInt(p.vrf.dlen.W)); val resultRows=Mem(maxRows,UInt(p.vrf.dlen.W))
  // One write port (capture, update and finalize are exclusive states), so
  // the scratch maps to distributed RAM instead of 16 x DLEN flip-flops.
  val resultWrite=WireDefault(false.B)
  val resultWriteRow=WireDefault(0.U(scratchBits.W)); val resultWriteData=WireDefault(0.U(p.vrf.dlen.W))
  when(resultWrite) { resultRows.write(resultWriteRow,resultWriteData) }
  val sendRow=RegInit(0.U((scratchBits+1).W)); val received=RegInit(0.U((scratchBits+1).W))
  val element=Reg(UInt((p.vrf.lengthBits+1).W)); val selectedIndex=Reg(UInt(64.W)); val selectedValid=Reg(Bool())
  val elementActive=Reg(Bool()); val a=Reg(UInt(64.W)); val b=Reg(UInt(64.W)); val old=Reg(UInt(64.W))
  val product=Reg(UInt(64.W)); val value=Reg(UInt(64.W))
  val saturated=RegInit(false.B); val flags=RegInit(0.U(5.W))
  val accumulator=Reg(UInt(64.W)); val prefix=Reg(UInt(64.W)); val seen=Reg(Bool()); val first=Reg(UInt(64.W))
  val scalarData=Reg(UInt(64.W)); val anyWrite=RegInit(false.B)
  val writeRow=Reg(UInt((scratchBits+1).W)); val acknowledgements=Reg(UInt((scratchBits+1).W))
  val x=d.issue.scalar.instruction; val op=d.operation
  def oneOf(names: Int*): Bool=names.map(n=>op===n.U).reduce(_ || _)
  val scalarMove=oneOf(ZveOp.vmv,ZveOp.vfmv) && x(31,26)===16.U
  val toScalar=scalarMove && d.scalarResult
  val fromScalar=scalarMove && !d.scalarResult
  val wholeMove=d.whole && op=/=ZveOp.memory.U
  val floatReduction=oneOf(ZveOp.vfredosum,ZveOp.vfredusum,ZveOp.vfredmin,ZveOp.vfredmax)
  val floatCompare=oneOf(ZveOp.vmfeq,ZveOp.vmfne,ZveOp.vmflt,ZveOp.vmfle,ZveOp.vmfgt,ZveOp.vmfge)
  val limit=Mux(toScalar,1.U,Mux(fromScalar,Mux(d.issue.scalar.vstart<d.issue.scalar.vl,1.U,0.U),
    Mux(wholeMove,d.destinationBytes>>d.sew,d.issue.scalar.vl)))
  val srcARows=Mux(d.vectorSource2,d.source2Registers*p.vrf.rowsPerReg.U,0.U)
  val srcBRows=Mux(d.vectorSource1,d.source1Registers*p.vrf.rowsPerReg.U,0.U)
  val dstRows=Mux(d.scalarResult,0.U,d.destinationRegisters*p.vrf.rowsPerReg.U)
  val maxAB=Mux(srcARows>srcBRows,srcARows,srcBRows)
  val captureRows=Mux(maxAB>dstRows,maxAB,dstRows)
  val vd=x(11,7); val vs1=x(19,15); val vs2=x(24,20)
  io.in.ready := state===idle
  when(io.in.fire) {
    d := io.in.bits; mask := io.mask; sendRow := 0.U; received := 0.U
    flags := 0.U; saturated := false.B; anyWrite := false.B
    state := capture
  }
  io.vrf.read.valid := state===capture && sendRow<captureRows
  io.vrf.read.bits.tag := sendRow; io.vrf.read.bits.age := d.age
  io.vrf.read.bits.rows(0) := (vs2*p.vrf.rowsPerReg.U+sendRow)(p.vrf.rowBits-1,0)
  io.vrf.read.bits.rows(1) := (vs1*p.vrf.rowsPerReg.U+sendRow)(p.vrf.rowBits-1,0)
  io.vrf.read.bits.rows(2) := (vd*p.vrf.rowsPerReg.U+sendRow)(p.vrf.rowBits-1,0)
  io.vrf.read.bits.enables := Cat(sendRow<dstRows,sendRow<srcBRows,sendRow<srcARows)
  when(io.vrf.read.fire) { sendRow := sendRow+1.U }
  io.vrf.returned.ready := state===capture
  when(io.vrf.returned.fire) {
    val r=io.vrf.returned.bits.tag(scratchBits-1,0)
    assert(io.vrf.returned.bits.tag===received,"snapshot read changed ownership/order")
    aRows.write(r,io.vrf.returned.bits.data(0)); bRows.write(r,io.vrf.returned.bits.data(1))
    resultWrite := true.B; resultWriteRow := r; resultWriteData := io.vrf.returned.bits.data(2)
    received := received+1.U
    when(received+1.U===captureRows) { state := initialize }
  }
  when(state===capture && captureRows===0.U) { state := initialize }
  def get(rows: Mem[UInt],index: UInt,width: UInt): UInt = {
    val byte=index.pad(p.vrf.lengthBits+1)<<width
    val r=(byte>>log2Ceil(p.vrf.rowBytes)).pad(scratchBits)(scratchBits-1,0)
    val offset=byte(log2Ceil(p.vrf.rowBytes)-1,0)
    val v=(rows.read(r)>>(offset<<3))(31,0)
    MuxLookup(width,v)(Seq(0.U->v(7,0).pad(32),1.U->v(15,0).pad(32))).pad(64)
  }
  def getBit(rows: Mem[UInt],index: UInt): Bool =
    (rows.read((index>>log2Ceil(p.vrf.dlen)).pad(scratchBits)(scratchBits-1,0))>>index.pad(log2Ceil(p.vrf.dlen))(log2Ceil(p.vrf.dlen)-1,0))(0)
  def sign(v: UInt,width: UInt): SInt = MuxLookup(width,v(31,0).asSInt.pad(64))(Seq(
    0.U->v(7,0).asSInt.pad(64),1.U->v(15,0).asSInt.pad(64)))
  val scalarFloat=Mux(d.issue.fpScalar(63,32).andR,d.issue.fpScalar(31,0),"h7fc00000".U)
  val scalar=Mux(x(14,12)===5.U,scalarFloat.pad(64),d.issue.scalar.rs1)
  val bValue=Mux(d.vectorSource1,Mux(op===ZveOp.vcompress.U,getBit(bRows,element).asUInt,
    Mux(d.maskSources,getBit(bRows,element).asUInt,get(bRows,element,d.source1Width))),scalar)
  val offset=Mux(x(14,12)===3.U,x(19,15).pad(64),d.issue.scalar.rs1)
  val sourceLimit=d.source2Bytes>>d.source2Width
  when(state===initialize) {
    element := 0.U; prefix := 0.U; seen := false.B; first := "hffffffffffffffff".U; scalarData := 0.U
    accumulator := get(bRows,0.U,d.source1Width)
    state := Mux(limit===0.U,finish,select)
  }
  when(state===select) {
    val down=element+&offset
    val index=Mux(oneOf(ZveOp.vrgather,ZveOp.vrgatherei16),bValue,
      Mux(op===ZveOp.vslideup.U,element-offset,
      Mux(op===ZveOp.vslidedown.U,down,
      Mux(oneOf(ZveOp.vslide1up,ZveOp.vfslide1up),element-1.U,
      Mux(oneOf(ZveOp.vslide1down,ZveOp.vfslide1down),element+1.U,element)))))
    selectedIndex := Mux(index<sourceLimit,index,0.U)
    selectedValid := index<sourceLimit
    b := bValue
    elementActive := Mux(toScalar || fromScalar,true.B,
      element>=d.issue.scalar.vstart && (x(25) || d.noPredication || (mask>>element)(0))) &&
      !(op===ZveOp.vslideup.U && element<offset)
    state := operands
  }
  when(state===operands) {
    a := Mux(d.maskSources,getBit(aRows,element).asUInt,
      Mux(selectedValid,get(aRows,selectedIndex,d.source2Width),0.U))
    old := get(resultRows,element,d.destinationWidth)
    state := multiply
  }
  val (mulA,mulB)=ZveIntegerArithmetic.multiplyOperands(d,a,b,old)
  when(state===multiply) { product := (mulA*mulB).asUInt; state := execute }
  val carry=(mask>>element)(0)
  val (arithmetic,arithmeticSat)=ZveIntegerArithmetic(d,a,b,old,product,Mux(x(25),false.B,carry))
  val estimate=Module(new ZveFpEstimate)
  estimate.io.value := a; estimate.io.rounding := d.issue.scalar.frm
  estimate.io.reciprocalSqrt := op===ZveOp.vfrsqrt7.U
  val divider=Module(new ZveDivider)
  divider.io.in.valid := state===execute && elementActive && oneOf(ZveOp.vdiv,ZveOp.vdivu,ZveOp.vrem,ZveOp.vremu)
  divider.io.in.bits.a := a; divider.io.in.bits.b := b
  divider.io.in.bits.signed := oneOf(ZveOp.vdiv,ZveOp.vrem)
  divider.io.in.bits.remainder := oneOf(ZveOp.vrem,ZveOp.vremu); divider.io.in.bits.sew := d.sew
  divider.io.out.ready := state===divide
  io.fp.request.valid := state===fpSend
  io.fp.request.bits := 0.U.asTypeOf(new ZveFpRequest)
  io.fp.request.bits.tag := element
  io.fp.request.bits.rounding := d.issue.scalar.frm; io.fp.request.bits.sourceFormat := 0.U
  io.fp.request.bits.destinationFormat := 0.U; io.fp.request.bits.integerFormat := 2.U
  io.fp.request.bits.operation := Mux(oneOf(ZveOp.vfredmin,ZveOp.vfredmax),BreezeFpOp.MINMAX.U,BreezeFpOp.ADD.U)
  io.fp.request.bits.a := Cat("hffffffff".U(32.W),accumulator(31,0))
  io.fp.request.bits.b := Cat("hffffffff".U(32.W),a(31,0))
  io.fp.request.bits.c := Cat("hffffffff".U(32.W),a(31,0))
  when(oneOf(ZveOp.vfredosum,ZveOp.vfredusum)) {
    io.fp.request.bits.a := 0.U; io.fp.request.bits.b := Cat("hffffffff".U(32.W),accumulator(31,0))
  }
  when(oneOf(ZveOp.vfredmin,ZveOp.vfredmax)) { io.fp.request.bits.rounding := Mux(op===ZveOp.vfredmax.U,1.U,0.U) }
  when(floatCompare) {
    io.fp.request.bits.operation := BreezeFpOp.CMP.U
    val swap=oneOf(ZveOp.vmfgt,ZveOp.vmfge)
    io.fp.request.bits.a := Cat("hffffffff".U(32.W),Mux(swap,b,a)(31,0))
    io.fp.request.bits.b := Cat("hffffffff".U(32.W),Mux(swap,a,b)(31,0))
    io.fp.request.bits.rounding := Mux(oneOf(ZveOp.vmfeq,ZveOp.vmfne),2.U,
      Mux(oneOf(ZveOp.vmflt,ZveOp.vmfgt),1.U,0.U))
  }
  io.fp.response.ready := state===fpWait
  when(state===fpSend && io.fp.request.fire) { state := fpWait }
  when(state===fpWait && io.fp.response.fire) {
    assert(io.fp.response.bits.tag===element,"FP reduction response has the wrong element")
    when(floatCompare) { value := io.fp.response.bits.data ^ (op===ZveOp.vmfne.U).asUInt }
      .otherwise { accumulator := io.fp.response.bits.data }
    flags := flags | io.fp.response.bits.flags; state := update
  }
  when(state===divide && divider.io.out.fire) { value := divider.io.out.bits; state := update }
  val reduction=WireDefault(accumulator)
  switch(op) {
    is(ZveOp.vredsum.U,ZveOp.vwredsumu.U) { reduction := accumulator+a }
    is(ZveOp.vwredsum.U) { reduction := accumulator+sign(a,d.source2Width).asUInt }
    is(ZveOp.vredand.U) { reduction := accumulator & a }
    is(ZveOp.vredor.U) { reduction := accumulator | a }
    is(ZveOp.vredxor.U) { reduction := accumulator ^ a }
    is(ZveOp.vredminu.U) { reduction := Mux(accumulator<a,accumulator,a) }
    is(ZveOp.vredmaxu.U) { reduction := Mux(accumulator>a,accumulator,a) }
    is(ZveOp.vredmin.U) { reduction := Mux(sign(accumulator,d.destinationWidth)<sign(a,d.source2Width),accumulator,a) }
    is(ZveOp.vredmax.U) { reduction := Mux(sign(accumulator,d.destinationWidth)>sign(a,d.source2Width),accumulator,a) }
  }
  when(state===execute) {
    value := arithmetic
    when(elementActive) { saturated := saturated || arithmeticSat }
    when(d.reduction && elementActive && !floatReduction) { accumulator := reduction }
    when(oneOf(ZveOp.vcpop,ZveOp.viota,ZveOp.vfirst,ZveOp.vmsbf,ZveOp.vmsif,ZveOp.vmsof) && elementActive) {
      when(a(0)) {
        prefix := prefix+1.U
        when(!seen) { first := element }
        seen := true.B
      }
    }
    switch(op) {
      is(ZveOp.vmand.U) { value := a(0) && b(0) }
      is(ZveOp.vmandn.U) { value := a(0) && !b(0) }
      is(ZveOp.vmor.U) { value := a(0) || b(0) }
      is(ZveOp.vmorn.U) { value := a(0) || !b(0) }
      is(ZveOp.vmxor.U) { value := a(0) ^ b(0) }
      is(ZveOp.vmnand.U) { value := !(a(0) && b(0)) }
      is(ZveOp.vmnor.U) { value := !(a(0) || b(0)) }
      is(ZveOp.vmxnor.U) { value := !(a(0) ^ b(0)) }
      is(ZveOp.vmsbf.U) { value := !seen && !a(0) }
      is(ZveOp.vmsif.U) { value := !seen }
      is(ZveOp.vmsof.U) { value := !seen && a(0) }
      is(ZveOp.viota.U) { value := prefix }
      is(ZveOp.vid.U) { value := element }
      is(ZveOp.vslideup.U,ZveOp.vslidedown.U,ZveOp.vrgather.U,ZveOp.vrgatherei16.U,
        ZveOp.vmv1r.U,ZveOp.vmv2r.U,ZveOp.vmv4r.U,ZveOp.vmv8r.U) { value := a }
      is(ZveOp.vslide1up.U,ZveOp.vfslide1up.U) { value := Mux(element===0.U,scalar,a) }
      is(ZveOp.vslide1down.U,ZveOp.vfslide1down.U) { value := Mux(element+1.U===limit,scalar,a) }
      is(ZveOp.vcompress.U) { value := a }
      is(ZveOp.vmerge.U) { value := Mux(carry,b,a) }
      is(ZveOp.vfmerge.U) { value := Mux(carry,scalar,a) }
      is(ZveOp.vfmv.U) { value := Mux(d.scalarResult,a,scalar) }
      is(ZveOp.vmv.U) { value := Mux(d.scalarResult,a,b) }
    }
    when(toScalar) { scalarData := Mux(d.scalarFloating,Cat("hffffffff".U(32.W),a(31,0)),sign(a,d.sew).asUInt) }
    when(fromScalar) { value := scalar }
    when(oneOf(ZveOp.vfrec7,ZveOp.vfrsqrt7)) {
      value := estimate.io.result
      when(elementActive) { flags := flags | estimate.io.flags }
    }
    when(divider.io.in.valid) { when(divider.io.in.fire) { state := divide } }
      .elsewhen((floatReduction || floatCompare) && elementActive) { state := fpSend }
      .otherwise { state := update }
  }
  val destinationIndex=Mux(op===ZveOp.vcompress.U,prefix,Mux(fromScalar,0.U,element))
  val destinationByte=destinationIndex<<d.destinationWidth
  val destinationRow=(destinationByte>>log2Ceil(p.vrf.rowBytes))(scratchBits-1,0)
  val destinationOffset=destinationByte(log2Ceil(p.vrf.rowBytes)-1,0)<<3
  val destinationBits=MuxLookup(d.destinationWidth,32.U)(Seq(0.U->8.U,1.U->16.U))
  val elementMask=(1.U((p.vrf.dlen+1).W)<<destinationBits)-1.U
  val dataMask=(elementMask<<destinationOffset)(p.vrf.dlen-1,0)
  val updated=(resultRows.read(destinationRow) & ~dataMask) |
    ((value & elementMask).pad(p.vrf.dlen)<<destinationOffset)(p.vrf.dlen-1,0)
  val packedRow=(element>>log2Ceil(p.vrf.dlen)).pad(scratchBits)(scratchBits-1,0)
  val packedBit=element(log2Ceil(p.vrf.dlen)-1,0)
  val packedMask=(1.U(p.vrf.dlen.W)<<packedBit)(p.vrf.dlen-1,0)
  val packedUpdated=(resultRows.read(packedRow) & ~packedMask) | Mux(value(0),packedMask,0.U)
  when(state===update) {
    val compressSelected=op=/=ZveOp.vcompress.U || b(0)
    when(elementActive && !d.scalarResult && !d.reduction && compressSelected) {
      resultWrite := true.B
      resultWriteRow := Mux(d.maskResult,packedRow,destinationRow)
      resultWriteData := Mux(d.maskResult,packedUpdated,updated)
      anyWrite := true.B
      when(op===ZveOp.vcompress.U) { prefix := prefix+1.U }
    }
    element := element+1.U
    when(element+1.U>=limit) { state := finalizeResult }.otherwise { state := select }
  }
  when(state===finalizeResult) {
    when(d.reduction && limit=/=0.U) {
      val m=(1.U((p.vrf.dlen+1).W)<<destinationBits)-1.U
      resultWrite := true.B; resultWriteRow := 0.U
      resultWriteData := (resultRows.read(0.U) & ~m(p.vrf.dlen-1,0)) | (accumulator.pad(p.vrf.dlen+1) & m)(p.vrf.dlen-1,0)
      anyWrite := true.B
    }
    writeRow := 0.U; acknowledgements := 0.U; state := write
  }
  io.vrf.write.valid := state===write && anyWrite && writeRow<dstRows
  io.vrf.write.bits.tag := writeRow; io.vrf.write.bits.age := d.age
  io.vrf.write.bits.row := (vd*p.vrf.rowsPerReg.U+writeRow)(p.vrf.rowBits-1,0)
  io.vrf.write.bits.data := resultRows.read(writeRow(scratchBits-1,0))
  io.vrf.write.bits.enables := Fill(p.vrf.rowBytes,1.U(1.W))
  when(io.vrf.write.fire) { writeRow := writeRow+1.U }
  io.vrf.written.ready := state===write
  when(io.vrf.written.fire) {
    assert(io.vrf.written.bits.tag===acknowledgements,"element write ack changed ownership/order")
    acknowledgements := acknowledgements+1.U
  }
  when(state===write && (!anyWrite || acknowledgements===dstRows)) { state := finish }
  io.complete.valid := state===finish
  io.complete.bits := 0.U.asTypeOf(new ZveCompletion(p))
  io.complete.bits.slot := d.slot; io.complete.bits.age := d.age
  io.complete.bits.flags := flags; io.complete.bits.saturated := saturated
  io.complete.bits.scalarValid := d.scalarResult; io.complete.bits.scalarFloating := d.scalarFloating
  io.complete.bits.scalarRd := vd
  io.complete.bits.scalarData := Mux(op===ZveOp.vcpop.U,prefix,Mux(op===ZveOp.vfirst.U,first,scalarData))
  when(io.complete.fire) { state := idle }
  io.drained := state===idle
}
