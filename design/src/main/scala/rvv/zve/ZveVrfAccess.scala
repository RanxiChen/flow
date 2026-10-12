package flow.rvv.zve

import chisel3._
import chisel3.util._
import flow.rvv.{RvvAge,RvvRegisterFile}

class ZveReadRequest(p: ZveParams) extends Bundle {
  val tag=UInt(16.W); val age=UInt(p.ageBits.W)
  val rows=Vec(3,UInt(p.vrf.rowBits.W)); val enables=UInt(3.W)
}
class ZveReadResponse(p: ZveParams) extends Bundle {
  val tag=UInt(16.W); val data=Vec(3,UInt(p.vrf.dlen.W))
}
class ZveWriteRequest(p: ZveParams) extends Bundle {
  val tag=UInt(16.W); val age=UInt(p.ageBits.W)
  val row=UInt(p.vrf.rowBits.W); val data=UInt(p.vrf.dlen.W)
  val enables=UInt(p.vrf.rowBytes.W)
}
class ZveWriteAck(p: ZveParams) extends Bundle {
  val tag=UInt(16.W); val row=UInt(p.vrf.rowBits.W)
}
class ZveVrfPort(p: ZveParams) extends Bundle {
  val read=Decoupled(new ZveReadRequest(p))
  val returned=Flipped(Decoupled(new ZveReadResponse(p)))
  val write=Decoupled(new ZveWriteRequest(p))
  val written=Flipped(Decoupled(new ZveWriteAck(p)))
}

/** Six existing physical read replicas service two triplets per cycle.
  * Request grants and all occupancy/ownership are registered. Read/write
  * result space is reserved at acceptance; physical writes generate the ack. */
class ZveVrfAccess(p: ZveParams, clients: Int = 6) extends Module {
  require(p.vrf.execReadPorts+2 >= 6)
  val io=IO(new Bundle {
    val ports=Vec(clients,Flipped(new ZveVrfPort(p)))
    val mask=Output(UInt(p.vrf.vlen.W)); val drained=Output(Bool())
  })
  val rf=Module(new RvvRegisterFile(p.vrf,clients))
  io.mask := rf.io.mask
  val readCount=RegInit(VecInit(Seq.fill(clients)(0.U(3.W))))
  val writeCount=RegInit(VecInit(Seq.fill(clients)(0.U(3.W))))
  val grantValid=RegInit(VecInit(Seq.fill(2)(false.B)))
  val grants=Reg(Vec(2,UInt(math.max(1,log2Ceil(clients)).W)))
  val physical=RegNext(VecInit(rf.io.write.map(_.fire)),VecInit(Seq.fill(clients)(false.B)))
  val physicalRows=Reg(Vec(clients,UInt(p.vrf.rowBits.W)))
  rf.io.write.zipWithIndex.foreach { case(w,c) => when(w.fire) { physicalRows(c) := w.bits.row } }
  def blocked(c: Int): Bool = (0 until 3).map(r => io.ports(c).read.bits.enables(r) &&
    (0 until clients).map(w => physical(w) && physicalRows(w) === io.ports(c).read.bits.rows(r)).reduce(_ || _)).reduce(_ || _)
  val eligible=(0 until clients).map(c => io.ports(c).read.valid && readCount(c)<4.U && !blocked(c))
  def oldest(candidates: Seq[Bool]): UInt = PriorityEncoder(VecInit((0 until clients).map(c =>
    candidates(c) && (0 until clients).filter(_ != c).map(o => !candidates(o) ||
      RvvAge.older(io.ports(c).read.bits.age,io.ports(o).read.bits.age) ||
      (io.ports(c).read.bits.age === io.ports(o).read.bits.age && (c<o).B)).foldLeft(true.B)(_ && _))).asUInt)
  val first=oldest(eligible)
  val secondEligible=eligible.zipWithIndex.map { case(e,c) => e && first =/= c.U }
  // Grants refresh only when their prior owners were accepted or are absent.
  // A held grant remains attached to the same request under a bank collision.
  for(g<-0 until 2) {
    val current=io.ports(grants(g)).read
    when(!grantValid(g) || current.fire || !current.valid) {
      grantValid(g) := (if(g==0) eligible.reduce(_ || _) else secondEligible.reduce(_ || _))
      grants(g) := (if(g==0) first else oldest(secondEligible))
    }
  }
  io.ports.foreach(_.read.ready := false.B)
  rf.io.readValid.foreach(_ := false.B); rf.io.readRows.foreach(_ := 0.U)
  val accepted=Wire(Vec(2,Bool()))
  for(g<-0 until 2) {
    val c=grants(g); val port=io.ports(c)
    val same=if(g==0) false.B else grantValid(0) && grants(0) === c
    val allowed=grantValid(g) && !same && readCount(c)<4.U &&
      !VecInit((0 until clients).map(blocked))(c)
    when(allowed) { port.read.ready := true.B }
    accepted(g) := allowed && port.read.valid
    for(r<-0 until 3) {
      rf.io.readValid(g*3+r) := accepted(g) && port.read.bits.enables(r)
      rf.io.readRows(g*3+r) := port.read.bits.rows(r)
    }
  }
  val owners=Seq.tabulate(2)(g => RegNext(RegNext(grants(g))))
  val tags=Seq.tabulate(2)(g => RegNext(RegNext(io.ports(grants(g)).read.bits.tag)))
  val returned=Seq.tabulate(2)(g => RegNext(RegNext(accepted(g),false.B),false.B))
  val readQueues=Seq.fill(clients)(Module(new Queue(new ZveReadResponse(p),4,pipe=false,flow=false)))
  val writeQueues=Seq.fill(clients)(Module(new Queue(new ZveWriteRequest(p),2,pipe=false,flow=false)))
  val ackQueues=Seq.fill(clients)(Module(new Queue(new ZveWriteAck(p),4,pipe=false,flow=false)))
  for(c<-0 until clients) {
    val port=io.ports(c)
    val rq=readQueues(c)
    val matches=VecInit((0 until 2).map(g => returned(g) && owners(g) === c.U))
    rq.io.enq.valid := matches.asUInt.orR
    rq.io.enq.bits.tag := Mux(matches(0),tags(0),tags(1))
    for(r<-0 until 3) { rq.io.enq.bits.data(r) := Mux(matches(0),rf.io.readData(r),rf.io.readData(3+r)) }
    assert(PopCount(matches)<=1.U,"two triplets returned to one client")
    when(rq.io.enq.valid) { assert(rq.io.enq.ready,"VRF read return exceeded reservation") }
    port.returned <> rq.io.deq
    when(port.read.fire =/= port.returned.fire) {
      readCount(c) := readCount(c)+port.read.fire.asUInt-port.returned.fire.asUInt
    }
    val wq=writeQueues(c)
    port.write.ready := wq.io.enq.ready && writeCount(c)<4.U
    wq.io.enq.valid := port.write.valid && writeCount(c)<4.U; wq.io.enq.bits := port.write.bits
    rf.io.write(c).valid := wq.io.deq.valid
    wq.io.deq.ready := rf.io.write(c).ready
    rf.io.write(c).bits.row := wq.io.deq.bits.row; rf.io.write(c).bits.data := wq.io.deq.bits.data
    rf.io.write(c).bits.enables := wq.io.deq.bits.enables; rf.io.age(c) := wq.io.deq.bits.age
    ackQueues(c).io.enq.valid := RegNext(rf.io.write(c).fire,false.B)
    ackQueues(c).io.enq.bits.tag := RegNext(wq.io.deq.bits.tag)
    ackQueues(c).io.enq.bits.row := RegNext(wq.io.deq.bits.row)
    when(ackQueues(c).io.enq.valid) { assert(ackQueues(c).io.enq.ready,"physical write ack exceeded reservation") }
    port.written <> ackQueues(c).io.deq
    when(port.write.fire =/= port.written.fire) {
      writeCount(c) := writeCount(c)+port.write.fire.asUInt-port.written.fire.asUInt
    }
    assert(readCount(c)<=4.U && writeCount(c)<=4.U,"VRF reservation accounting overflow")
  }
  io.drained := !readCount.map(_ =/= 0.U).reduce(_ || _) && !writeCount.map(_ =/= 0.U).reduce(_ || _)
}
