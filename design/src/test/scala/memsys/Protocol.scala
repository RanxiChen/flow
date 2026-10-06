package flow.memsys

import chisel3._
import chisel3.simulator.PeekPokeAPI._
import chisel3.util.DecoupledIO
import flow.coherence._

/** Scala mirrors of the four protocol messages and their poke/peek bindings. */
final case class Req(op: Int, addr: BigInt, id: Int = 0, mask: BigInt = 0, data: BigInt = 0)
final case class RspUp(op: Int, hasData: Boolean, addr: BigInt, data: BigInt = 0)
final case class Snp(op: Int, owner: Boolean, addr: BigInt)
final case class RspDown(op: Int, id: Int = 0, error: Boolean = false, data: BigInt = 0)

object Op {
  // REQ
  val GetS = 0; val GetM = 1; val Read = 2; val MaskWrite = 3
  // RSP↑
  val Put = 0; val InvAck = 1; val DownAck = 2
  // SNP
  val Inv = 0; val Down = 1
  // RSP↓
  val DataS = 0; val DataE = 1; val AckE = 2; val PutAck = 3; val ReadData = 4; val WriteAck = 5
}

object Bind {
  private def lit(d: Data): BigInt = d.asInstanceOf[Element] match {
    case b: Bool => if (b.peek().litToBoolean) 1 else 0
    case u: UInt => u.peek().litValue
    case e: EnumType => e.peek().litValue
  }

  def pokeReq(b: CoherenceReq, r: Req): Unit = {
    b.op.poke(ReqOp.all(r.op)); b.addr.poke(r.addr.U); b.id.poke(r.id.U)
    b.mask.poke(r.mask.U); b.data.poke(r.data.U)
  }
  def peekReq(b: CoherenceReq): Req =
    Req(lit(b.op).toInt, lit(b.addr), lit(b.id).toInt, lit(b.mask), lit(b.data))

  def pokeRspUp(b: CoherenceRspUp, r: RspUp): Unit = {
    b.op.poke(RspUpOp.all(r.op)); b.hasData.poke(r.hasData.B); b.addr.poke(r.addr.U); b.data.poke(r.data.U)
  }
  def peekRspUp(b: CoherenceRspUp): RspUp =
    RspUp(lit(b.op).toInt, lit(b.hasData) == 1, lit(b.addr), lit(b.data))

  def pokeSnp(b: CoherenceSnp, s: Snp): Unit = {
    b.op.poke(SnpOp.all(s.op)); b.owner.poke(s.owner.B); b.addr.poke(s.addr.U)
  }
  def peekSnp(b: CoherenceSnp): Snp = Snp(lit(b.op).toInt, lit(b.owner) == 1, lit(b.addr))

  def pokeRspDown(b: CoherenceRspDown, r: RspDown): Unit = {
    b.op.poke(RspDownOp.all(r.op)); b.id.poke(r.id.U); b.error.poke(r.error.B); b.data.poke(r.data.U)
  }
  def peekRspDown(b: CoherenceRspDown): RspDown =
    RspDown(lit(b.op).toInt, lit(b.id).toInt, lit(b.error) == 1, lit(b.data))

  def fired[T <: Data](d: DecoupledIO[T]): Boolean = d.valid.peek().litToBoolean && d.ready.peek().litToBoolean
  def valid[T <: Data](d: DecoupledIO[T]): Boolean = d.valid.peek().litToBoolean
}
