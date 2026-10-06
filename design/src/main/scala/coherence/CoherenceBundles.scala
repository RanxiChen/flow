package flow.coherence

import chisel3._
import chisel3.util._

/** Four-link protocol (coherence-l2-rtl-spec §1.2). Each message is one beat. */
object ReqOp extends ChiselEnum { val GetS, GetM, Read, MaskWrite = Value }
object RspUpOp extends ChiselEnum { val Put, InvAck, DownAck = Value }
object SnpOp extends ChiselEnum { val Inv, Down = Value }
object RspDownOp extends ChiselEnum { val DataS, DataE, AckE, PutAck, ReadData, WriteAck = Value }

/** L1D line state (§1.5). E→M is silent. */
object L1State extends ChiselEnum { val I, S, E, M = Value }
/** L2 directory state (§1.5). */
object DirState extends ChiselEnum { val NONE, SHARED, UNIQUE = Value }

class CoherenceReq(p: CoherenceParams) extends Bundle {
  val op = ReqOp()
  val addr = UInt(p.lineAddrBits.W)
  val id = UInt(1.W)
  val mask = UInt(p.lineBytes.W)
  val data = UInt(p.lineBits.W)
}

class CoherenceRspUp(p: CoherenceParams) extends Bundle {
  val op = RspUpOp()
  val hasData = Bool()
  val addr = UInt(p.lineAddrBits.W)
  val data = UInt(p.lineBits.W)
}

class CoherenceSnp(p: CoherenceParams) extends Bundle {
  val op = SnpOp()
  val owner = Bool()
  val addr = UInt(p.lineAddrBits.W)
}

class CoherenceRspDown(p: CoherenceParams) extends Bundle {
  val op = RspDownOp()
  val id = UInt(1.W)
  val error = Bool()
  val data = UInt(p.lineBits.W)
}

/** L1D side of one core's links. L2 uses Flipped. */
class L1DCoherenceIO(p: CoherenceParams) extends Bundle {
  val req = Decoupled(new CoherenceReq(p))
  val rspUp = Decoupled(new CoherenceRspUp(p))
  val snp = Flipped(Decoupled(new CoherenceSnp(p)))
  val rspDown = Flipped(Decoupled(new CoherenceRspDown(p)))
}

/** Read-only client (L1I, DMA): REQ + RSP↓. L2 uses Flipped. */
class ReadClientIO(p: CoherenceParams) extends Bundle {
  val req = Decoupled(new CoherenceReq(p))
  val rspDown = Flipped(Decoupled(new CoherenceRspDown(p)))
}
