package flow.bus

import chisel3._
import chisel3.util._
import flow.interface.{DCacheMemReqIO, DCacheMemRespIO}

object BreezeMmioArbiterState extends ChiselEnum {
  val Idle, Issue, WaitResp = Value
}

/** Blocking round-robin arbiter for per-hart uncached/MMIO scalar requests.
  *
  * Every hart's D$ emits its uncached request as a one-cycle pulse on a
  * dedicated point-to-point interface whose contract requires the receiver to
  * capture the pulse unconditionally. The arbiter therefore keeps a one-entry
  * pending register per hart (a hart's blocking D$ never emits a second pulse
  * before the first is answered) and serializes the pending requests onto the
  * single shared MMIO master with rotating priority. The shared master follows
  * the same pulse/response contract, so the arbiter issues exactly one pulse
  * per transaction and routes the single response back to the owning hart.
  */
class BreezeMmioArbiter(
    val numHarts: Int,
    val lineBytes: Int = 32
) extends Module {
  private val hartIdWidth = math.max(1, log2Ceil(numHarts))

  val io = IO(new Bundle {
    val hartReq = Flipped(Vec(numHarts, new DCacheMemReqIO(64, lineBytes)))
    val hartResp = Flipped(Vec(numHarts, new DCacheMemRespIO(lineBytes)))
    val memReq = new DCacheMemReqIO(64, lineBytes)
    val memResp = new DCacheMemRespIO(lineBytes)
  })

  import BreezeMmioArbiterState._

  val state = RegInit(Idle)
  val pendValid = RegInit(VecInit(Seq.fill(numHarts)(false.B)))
  val pendAddr = Reg(Vec(numHarts, UInt(64.W)))
  val pendIsWrite = Reg(Vec(numHarts, Bool()))
  val pendData = Reg(Vec(numHarts, UInt((lineBytes * 8).W)))
  val pendMask = Reg(Vec(numHarts, UInt(lineBytes.W)))
  val selHart = RegInit(0.U(hartIdWidth.W))
  val rrPtr = RegInit(0.U(hartIdWidth.W))

  // Unconditional per-hart pulse capture (the pulse contract).
  for (h <- 0 until numHarts) {
    when(io.hartReq(h).req) {
      assert(!pendValid(h),
        "MMIO arbiter: a hart issued a second uncached pulse before the first response")
      pendValid(h) := true.B
      pendAddr(h) := io.hartReq(h).addr
      pendIsWrite(h) := io.hartReq(h).isWrite
      pendData(h) := io.hartReq(h).data
      pendMask(h) := io.hartReq(h).mask
    }
  }

  io.memReq.req := false.B
  io.memReq.addr := 0.U
  io.memReq.isWrite := false.B
  io.memReq.isLine := false.B
  io.memReq.data := 0.U
  io.memReq.mask := 0.U
  for (h <- 0 until numHarts) {
    io.hartResp(h).vld := false.B
    io.hartResp(h).data := 0.U
    io.hartResp(h).error := false.B
  }

  // Round-robin selection starting at rrPtr (blocking: one transaction at a
  // time, so the scan is over a static snapshot). Rotate the pending vector
  // right by rrPtr so a fixed priority encoder yields rotating priority;
  // never read a wire that the same combinational block reassigns.
  val pendVec = pendValid.asUInt
  val selValid = pendVec.orR
  val selNext = if (numHarts == 1) {
    0.U(hartIdWidth.W)
  } else {
    val rotated = (Cat(pendVec, pendVec) >> rrPtr)(numHarts - 1, 0)
    (rrPtr + PriorityEncoder(rotated))(hartIdWidth - 1, 0)
  }

  switch(state) {
    is(Idle) {
      when(selValid) {
        selHart := selNext
        state := Issue
      }
    }

    is(Issue) {
      io.memReq.req := true.B
      io.memReq.addr := pendAddr(selHart)
      io.memReq.isWrite := pendIsWrite(selHart)
      io.memReq.isLine := false.B
      io.memReq.data := pendData(selHart)
      io.memReq.mask := pendMask(selHart)
      state := WaitResp
    }

    is(WaitResp) {
      when(io.memResp.vld) {
        io.hartResp(selHart).vld := true.B
        io.hartResp(selHart).data := io.memResp.data
        io.hartResp(selHart).error := io.memResp.error
        pendValid(selHart) := false.B
        rrPtr := Mux(selHart === (numHarts - 1).U, 0.U, selHart + 1.U)
        state := Idle
      }
    }
  }
}
