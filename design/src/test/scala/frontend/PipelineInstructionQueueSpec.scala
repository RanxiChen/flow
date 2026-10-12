package flow.frontend

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable
import scala.util.Random

class InstructionQueueHarness(p: BreezeFrontendPipelineParams) extends Module {
  private val width = (new PipelineInstruction(p)).getWidth
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val count = Input(UInt(2.W))
    val words = Input(Vec(2, UInt(width.W)))
    val ready = Input(Bool())
    val clear = Input(Bool())
    val accept = Output(Bool())
    val available = Output(Bool())
    val word = Output(UInt(width.W))
    val empty = Output(Bool())
  })
  val q = Module(new PipelineInstructionQueue(p))
  q.io.enq.valid := io.valid; q.io.enq.bits.count := io.count
  for (n <- 0 until 2) q.io.enq.bits.instructions(n) := io.words(n).asTypeOf(new PipelineInstruction(p))
  q.io.deq.ready := io.ready; q.io.clear := io.clear
  io.accept := q.io.enq.ready; io.available := q.io.deq.valid
  io.word := q.io.deq.bits.asUInt; io.empty := q.io.empty
}

class PipelineInstructionQueueSpec extends AnyFreeSpec with ChiselSim {
  for (slots <- Seq(4, 8, 16)) {
    s"atomic 0/1/2 enqueue preserves every payload bit through wrap, full replacement and cancellation ($slots slots)" in {
      val p = BreezeFrontendPipelineParams(instructionSlots = slots)
      simulate(new InstructionQueueHarness(p)) { d =>
        d.io.valid.poke(false.B); d.io.count.poke(0.U); d.io.words.foreach(_.poke(0.U))
        d.io.ready.poke(false.B); d.io.clear.poke(false.B)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        val rng = new Random(0x8212L + slots)
        val expected = mutable.Queue.empty[BigInt]
        var pending = Option.empty[Seq[BigInt]]
        var replacements = 0
        var zeroPackets = 0
        for (cycle <- 0 until 1600) {
          val epoch = cycle % 200
          if (epoch == 39 || epoch == 150) {
            d.io.clear.poke(true.B); d.io.valid.poke(true.B); d.io.ready.poke(true.B)
            d.io.accept.expect(false.B); d.io.available.expect(false.B)
            d.clock.step(); expected.clear(); pending = None; d.io.clear.poke(false.B)
          }
          val fill = epoch < slots / 2 + 4
          if (pending.isEmpty && (fill || rng.nextInt(4) != 0)) {
            val n = if (fill) 2 else rng.nextInt(3)
            pending = Some(Seq.fill(n)(BigInt(d.io.words(0).getWidth, rng)))
          }
          val ready = !fill && rng.nextBoolean()
          val n = pending.fold(0)(_.size)
          d.io.valid.poke(pending.nonEmpty.B); d.io.count.poke(n.U); d.io.ready.poke(ready.B)
          for (k <- 0 until 2) d.io.words(k).poke(pending.flatMap(_.lift(k)).getOrElse(BigInt(0)).U)
          val pop = ready && expected.nonEmpty
          val accept = slots - expected.size + (if (pop) 1 else 0) >= n
          d.io.accept.expect(accept.B); d.io.available.expect(expected.nonEmpty.B)
          d.io.empty.expect(expected.isEmpty.B)
          if (expected.nonEmpty) d.io.word.expect(expected.front.U)
          if (expected.size == slots && pop && pending.nonEmpty && accept && n == 1) replacements += 1
          if (pop) expected.dequeue()
          if (pending.nonEmpty && accept) {
            if (n == 0) zeroPackets += 1
            expected ++= pending.get; pending = None
          }
          d.clock.step()
        }
        assert(replacements > 0 && zeroPackets > 0)
      }
    }
  }
}
