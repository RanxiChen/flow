package flow.backend

import chisel3._
import chisel3.util._

class IntMduResult extends Bundle {
  val rd = UInt(5.W)
  val data = UInt(64.W)
}

class IntMulRequest extends Bundle {
  val a = SInt(65.W)
  val b = SInt(65.W)
  val op = UInt(3.W)
  val rd = UInt(5.W)
}

class IntDivRequest extends Bundle {
  val dividendMag = UInt(64.W)
  val divisorMag = UInt(64.W)
  val quotientNeg = Bool()
  val remainderNeg = Bool()
  val isRemainder = Bool()
  val isWord = Bool()
  val fastValid = Bool()
  val fastData = UInt(64.W)
  val rd = UInt(5.W)
}

class IntMduIO[T <: Data](request: T) extends Bundle {
  val req = Flipped(Decoupled(request))
  val commit = Input(Bool())
  val killUncommitted = Input(Bool())
  val result = Decoupled(new IntMduResult)
}
