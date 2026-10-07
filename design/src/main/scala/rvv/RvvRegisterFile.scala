package flow.rvv

import chisel3._
import chisel3.util._

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
    Module(new RvvVrfBank(p.rows/p.writeBanks,p.dlen))
  }
  def bank(row: UInt): UInt = if(bankBits == 0) 0.U else row(bankBits-1,0)
  def index(row: UInt): UInt = row >> bankBits
  for(r <- 0 until p.execReadPorts+2) {
    banks.foreach(copies => copies(r).io.readAddress := index(io.readRows(r)))
    io.readData(r) := VecInit(banks.map(copies => copies(r).io.readData))(bank(io.readRows(r)))
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
    // One logical write port per bank, broadcast to its read replicas.
    banks(b).foreach { copy =>
      copy.io.clock := clock; copy.io.writeValid := bankValid
      copy.io.writeAddress := index(bankWrite.row)
      copy.io.writeData := bankWrite.data; copy.io.writeEnables := bankWrite.enables
    }
    when(bankValid) {
      val x=bankWrite
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

/** Technology-neutral 1R/1W asynchronous byte-write RAM template. The Vivado
  * attribute selects the frozen LUTRAM implementation; Verilator executes the
  * same uninitialized-memory and edge-write semantics as the Chisel Mem. */
class RvvVrfBank(rows: Int,dataBits: Int) extends BlackBox(Map(
  "ROWS" -> rows,"DATA_BITS" -> dataBits,"ADDR_BITS" -> math.max(1,log2Ceil(rows)))) with HasBlackBoxInline {
  private val addrBits=math.max(1,log2Ceil(rows))
  val io=IO(new Bundle {
    val clock=Input(Clock())
    val readAddress=Input(UInt(addrBits.W)); val readData=Output(UInt(dataBits.W))
    val writeValid=Input(Bool()); val writeAddress=Input(UInt(addrBits.W))
    val writeData=Input(UInt(dataBits.W)); val writeEnables=Input(UInt((dataBits/8).W))
  })
  setInline("RvvVrfBank.sv","""module RvvVrfBank #(
    |  parameter int ROWS=8, DATA_BITS=512, ADDR_BITS=3
    |)(
    |  input logic clock,
    |  input logic [ADDR_BITS-1:0] readAddress,
    |  output logic [DATA_BITS-1:0] readData,
    |  input logic writeValid,
    |  input logic [ADDR_BITS-1:0] writeAddress,
    |  input logic [DATA_BITS-1:0] writeData,
    |  input logic [DATA_BITS/8-1:0] writeEnables
    |);
    |  (* ram_style = "distributed" *) logic [DATA_BITS-1:0] memory [0:ROWS-1];
    |  assign readData=memory[readAddress];
    |  always @(posedge clock) begin
    |    for(integer b=0;b<DATA_BITS/8;b=b+1)
    |      if(writeValid && writeEnables[b]) memory[writeAddress][8*b +: 8] <= writeData[8*b +: 8];
    |  end
    |endmodule
    |""".stripMargin)
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
