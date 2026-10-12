package flow.util

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable
import scala.util.Random

class PairQueueComparison(pipe: Boolean) extends Module {
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val valid = Input(Bool())
    val data = Input(UInt(282.W))
    val ready = Input(Bool())
    val accept = Output(Vec(2, Bool()))
    val available = Output(Vec(2, Bool()))
    val value = Output(Vec(2, UInt(282.W)))
    val count = Output(Vec(2, UInt(2.W)))
  })
  val pair = withReset(reset.asBool || io.clear) {
    Module(new RegisteredPairQueue(UInt(282.W), pipe))
  }
  val original = withReset(reset.asBool || io.clear) {
    Module(new Queue(UInt(282.W), 2, pipe = pipe, flow = false))
  }
  pair.io.enq.valid := io.valid; original.io.enq.valid := io.valid
  pair.io.enq.bits := io.data; original.io.enq.bits := io.data
  pair.io.deq.ready := io.ready; original.io.deq.ready := io.ready
  io.accept := VecInit(Seq(pair.io.enq.ready, original.io.enq.ready))
  io.available := VecInit(Seq(pair.io.deq.valid, original.io.deq.valid))
  io.value := VecInit(Seq(pair.io.deq.bits, original.io.deq.bits))
  io.count := VecInit(Seq(pair.io.count, original.io.count))
}

class RegisteredPairQueueSpec extends AnyFreeSpec with ChiselSim {
  for (pipe <- Seq(false, true)) {
    s"pair FIFO preserves original Queue cycles, order and cancellation pipe=$pipe" in {
      simulate(new PairQueueComparison(pipe)) { d =>
        d.io.clear.poke(false.B); d.io.valid.poke(false.B)
        d.io.data.poke(0.U); d.io.ready.poke(false.B)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        val rng = new Random(0x28271L)
        val expected = mutable.Queue.empty[BigInt]
        var pending = Option.empty[BigInt]
        var fullReplace = 0
        var heldHead = 0
        for (cycle <- 0 until 2000) {
          val epoch = cycle % 400
          if (epoch == 100 || epoch == 309) {
            d.io.valid.poke(false.B); d.io.clear.poke(true.B)
            d.clock.step(); expected.clear(); pending = None
            d.io.clear.poke(false.B)
          }
          val fill = epoch < 8 || (epoch >= 300 && epoch < 309)
          val drain = epoch >= 8 && epoch < 16
          if (pending.isEmpty && !drain && (fill || rng.nextInt(4) != 0))
            pending = Some(BigInt(282, rng))
          val ready = if (fill) false else if (drain) true else rng.nextBoolean()
          d.io.valid.poke(pending.nonEmpty.B)
          d.io.data.poke(pending.getOrElse(BigInt(0)).U)
          d.io.ready.poke(ready.B)
          val accept = expected.size < 2 || (pipe && ready)
          for (n <- 0 until 2) {
            d.io.accept(n).expect(accept.B)
            d.io.available(n).expect(expected.nonEmpty.B)
            d.io.count(n).expect(expected.size.U)
            if (expected.nonEmpty) d.io.value(n).expect(expected.front.U)
          }
          if (expected.size == 2 && ready && pending.nonEmpty && accept) fullReplace += 1
          if (expected.nonEmpty && !ready) heldHead += 1
          if (ready && expected.nonEmpty) expected.dequeue()
          if (pending.nonEmpty && accept) { expected.enqueue(pending.get); pending = None }
          d.clock.step()
        }
        assert(heldHead > 0)
        if (pipe) assert(fullReplace > 0)
      }
    }
  }
}
