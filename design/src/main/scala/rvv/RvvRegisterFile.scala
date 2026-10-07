package flow.rvv

import chisel3._
import chisel3.util._
import chisel3.experimental.annotate

/** Byte-enable memory-order rows. Asynchronous read ports infer replicated
  * distributed RAM; each low-address bank has exactly one accepted writer. */
class RvvRegisterFile(p: RvvParams, writers: Int = 3) extends Module {
  val io = IO(new Bundle {
    val readRows = Input(Vec(p.execReadPorts+2,UInt(p.rowBits.W)))
    val readData = Output(Vec(p.execReadPorts+2,UInt(p.dlen.W)))
    val write = Vec(writers,Flipped(Decoupled(new RvvWrite(p))))
    val age = Input(Vec(writers,UInt(64.W)))
    val mask = Output(UInt(p.vlen.W))
  })
  val shadow = RegInit(0.U(p.vlen.W)); io.mask := shadow
  val shadowBytes = Wire(Vec(p.regBytes,UInt(8.W)))
  shadowBytes := shadow.asTypeOf(Vec(p.regBytes,UInt(8.W)))
  shadow := shadowBytes.asUInt
  val bankBits = log2Ceil(p.writeBanks)
  // A single six-read Mem can fall back to flip-flops in Vivado. Construct the
  // documented read replicas explicitly: each copy has one read and one write.
  val banks = Seq.tabulate(p.writeBanks,p.execReadPorts+2) { (_,_) =>
    val m=Mem(p.rows/p.writeBanks,Vec(p.rowBytes,UInt(8.W)))
    annotate(m)(Seq(firrtl.AttributeAnnotation(m.toNamed,"ram_style = \"distributed\"")))
    m
  }
  def bank(row: UInt): UInt = if(bankBits == 0) 0.U else row(bankBits-1,0)
  def index(row: UInt): UInt = row >> bankBits
  for(r <- 0 until p.execReadPorts+2) {
    io.readData(r) := VecInit(banks.map(copies => copies(r)(index(io.readRows(r))).asUInt))(bank(io.readRows(r)))
  }
  io.write.foreach(_.ready := false.B)
  for(b <- 0 until p.writeBanks) {
    val eligible = (0 until writers).map(w => io.write(w).valid && bank(io.write(w).bits.row) === b.U)
    val bankValid = WireDefault(false.B)
    val bankWrite = WireDefault(0.U.asTypeOf(new RvvWrite(p)))
    for(w <- 0 until writers) {
      val wins = eligible(w) && (0 until writers).filter(_ != w).map(o =>
        !eligible(o) || io.age(w) < io.age(o) || (io.age(w) === io.age(o) && (w < o).B)).foldLeft(true.B)(_ && _)
      when(wins) {
        io.write(w).ready := true.B
        bankValid := true.B; bankWrite := io.write(w).bits
      }
    }
    // Exactly one physical memory write port per bank, after arbitration.
    when(bankValid) {
      val x=bankWrite
      banks(b).foreach(_.write(index(x.row),x.data.asTypeOf(Vec(p.rowBytes,UInt(8.W))),x.enables.asBools))
      when(x.row < p.rowsPerReg.U) {
        for(j <- 0 until p.rowBytes) {
          when(x.enables(j)) { shadowBytes((x.row*p.rowBytes.U+j.U)(log2Ceil(p.regBytes)-1,0)) := x.data(8*j+7,8*j) }
        }
      }
    }
  }
  for(a <- 0 until writers;b <- a+1 until writers) {
    assert(!(io.write(a).fire && io.write(b).fire && io.write(a).bits.row === io.write(b).bits.row),
      "two writers accepted for the same VRF row")
  }
}

class RvvScoreboard(p: RvvParams, clients: Int = 4) extends Module {
  val io = IO(new Bundle {
    val allocate = Flipped(Decoupled(new RvvDescriptor(p)))
    val slot = Output(UInt(p.slotBits.W))
    val check = Input(Vec(clients,new RvvHazard(p)))
    val raw = Output(Vec(clients,Bool())); val war = Output(Vec(clients,Bool())); val waw = Output(Vec(clients,Bool()))
    val progress = Input(Vec(clients,Valid(new RvvProgress(p))))
    val empty = Output(Bool())
  })
  val valid = RegInit(VecInit(Seq.fill(p.scoreboardDepth)(false.B)))
  val ages = Reg(Vec(p.scoreboardDepth,UInt(64.W)))
  val reads = Reg(Vec(p.scoreboardDepth,UInt(32.W)))
  val writes = Reg(Vec(p.scoreboardDepth,UInt(32.W)))
  val free = PriorityEncoder(~valid.asUInt)
  io.slot := free; io.allocate.ready := !valid.asUInt.andR; io.empty := !valid.asUInt.orR
  for(c <- 0 until clients) {
    val q = io.check(c)
    val older = (0 until p.scoreboardDepth).map(j => valid(j) && ages(j) < ages(q.slot))
    val oldReads = (0 until p.scoreboardDepth).map(j => Mux(older(j),reads(j),0.U)).reduce(_ | _)
    val oldWrites = (0 until p.scoreboardDepth).map(j => Mux(older(j),writes(j),0.U)).reduce(_ | _)
    io.raw(c) := q.valid && (q.reads & oldWrites).orR
    io.war(c) := q.valid && (q.writes & oldReads).orR
    io.waw(c) := q.valid && (q.writes & oldWrites).orR
    val u = io.progress(c)
    when(u.valid) {
      assert(valid(u.bits.slot),"progress for a non-live instruction")
      reads(u.bits.slot) := reads(u.bits.slot) & ~u.bits.readDone
      writes(u.bits.slot) := writes(u.bits.slot) & ~u.bits.writeDone
      when(u.bits.finished) { valid(u.bits.slot) := false.B }
    }
  }
  when(io.allocate.fire) {
    valid(free) := true.B; ages(free) := io.allocate.bits.age
    reads(free) := io.allocate.bits.decoded.readMask; writes(free) := io.allocate.bits.decoded.writeMask
  }
}
