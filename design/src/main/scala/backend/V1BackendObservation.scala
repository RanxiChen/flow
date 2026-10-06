package flow.backend
import chisel3._
import chisel3.util._
/** Nonarchitectural observability. No transaction identity or buffering. */
class V1BackendObservation extends Bundle {
  val idLeave = Bool()
  val exFire = Bool()
  val exPc = UInt(64.W)
  val commit = Bool()
  val commitPc = UInt(64.W)
  val gprWrite = Valid(new V1RegisterWrite)
  val fprWrite = Valid(new V1RegisterWrite)
  val fpFlags = Valid(UInt(5.W))
  val gprBusy = UInt(32.W)
  val fprBusy = UInt(32.W)
  val memHold = Bool()
  val exHold = Bool()
  val grant = UInt(4.W)
  val fpIn = Bool()
  val fpOut = Bool()
  val mulIn = Bool()
  val divIn = Bool()
  val divIterating = Bool()
  val translationBlocked = Bool()
}
