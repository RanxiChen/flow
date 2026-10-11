package flow.rvv

import chisel3._
import chisel3.util._
import flow.bus._

/** Scalar-first arbitration with a bounded eight-burst run. Read response
  * routing uses a source ID; write data stays with its accepted AW owner.
  * Address ownership is latched whenever the registered address queue fills.
  * Two entries cut both arbitration-valid and downstream-ready feedback. An
  * accepted address belongs to the queue until the external AXI handshake;
  * the source reserves its response credit at this local acceptance boundary.
  */
class RvvAxiArbiter(addrBits: Int, dataBits: Int) extends Module {
  val in = Axi4Params(addrBits, dataBits, 1)
  val out = in.copy(idBits = 2)
  val io = IO(new Bundle {
    val scalar = Flipped(new Axi4MasterIO(in))
    val vector = Flipped(new Axi4MasterIO(in))
    val mem = new Axi4MasterIO(out)
  })
  val clients = Seq(io.scalar, io.vector)
  val readAddresses = Module(new Queue(new Axi4Ar(out), 2, pipe=false, flow=false))
  val writeAddresses = Module(new Queue(new Axi4Aw(out), 2, pipe=false, flow=false))
  io.mem.ar <> readAddresses.io.deq
  io.mem.aw <> writeAddresses.io.deq
  def address(read: Boolean, allowed: Bool): (Bool, Bool) = {
    val ports = clients.map(c => if (read) c.ar else c.aw)
    val target = if (read) readAddresses.io.enq else writeAddresses.io.enq
    val held = RegInit(false.B); val owner = Reg(Bool())
    val scalarRun = RegInit(0.U(4.W))
    val select = Mux(held, owner, ports(1).valid && (!ports(0).valid || scalarRun === 8.U))
    target.valid := allowed && Mux(select, ports(1).valid, ports(0).valid)
    target.bits := 0.U.asTypeOf(target.bits)
    val selected = Mux(select, ports(1).bits, ports(0).bits)
    target.bits.id := select ## selected.id
    target.bits.addr := selected.addr; target.bits.len := selected.len
    target.bits.size := selected.size; target.bits.burst := selected.burst; target.bits.prot := selected.prot
    ports(0).ready := allowed && target.ready && !select
    ports(1).ready := allowed && target.ready && select
    when(target.valid && !target.ready && !held) { held := true.B; owner := select }
    when(target.fire) {
      held := false.B
      when(select) { scalarRun := 0.U }
        .otherwise { when(scalarRun < 8.U) { scalarRun := scalarRun + 1.U } }
    }
    (target.fire, select)
  }
  address(true, true.B)
  val writeOwned = RegInit(false.B); val writeOwner = Reg(Bool())
  val (awFire, awOwner) = address(false, !writeOwned)
  when(awFire) { writeOwned := true.B; writeOwner := awOwner }
  io.mem.w.valid := writeOwned && Mux(writeOwner, io.vector.w.valid, io.scalar.w.valid)
  io.mem.w.bits := Mux(writeOwner, io.vector.w.bits, io.scalar.w.bits)
  io.scalar.w.ready := writeOwned && !writeOwner && io.mem.w.ready
  io.vector.w.ready := writeOwned && writeOwner && io.mem.w.ready
  when(io.mem.w.fire && io.mem.w.bits.last) { writeOwned := false.B }
  for ((c, index) <- clients.zipWithIndex) {
    c.r.valid := io.mem.r.valid && io.mem.r.bits.id(1) === index.U
    c.r.bits := 0.U.asTypeOf(c.r.bits)
    c.r.bits.id := io.mem.r.bits.id(0)
    c.r.bits.data := io.mem.r.bits.data; c.r.bits.last := io.mem.r.bits.last; c.r.bits.resp := io.mem.r.bits.resp
    c.b.valid := io.mem.b.valid && io.mem.b.bits.id(1) === index.U
    c.b.bits.id := io.mem.b.bits.id(0); c.b.bits.resp := io.mem.b.bits.resp
  }
  io.mem.r.ready := Mux(io.mem.r.bits.id(1), io.vector.r.ready, io.scalar.r.ready)
  io.mem.b.ready := Mux(io.mem.b.bits.id(1), io.vector.b.ready, io.scalar.b.ready)
}
