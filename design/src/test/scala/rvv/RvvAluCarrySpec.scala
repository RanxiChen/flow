package flow.rvv

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.util.Random

class RvvAluCarryHarness extends Module {
  val p=RvvParams()
  val io=IO(new Bundle {
    val a=Input(UInt(p.dlen.W)); val b=Input(UInt(p.dlen.W))
    val scalar=Input(UInt(64.W)); val scalarOperand=Input(Bool())
    val sew=Input(UInt(2.W)); val sub=Input(Bool()); val result=Output(UInt(p.dlen.W))
  })
  io.result := RvvAluArithmetic(p,io.a,io.b,io.scalar,io.sew,io.scalarOperand,
    Mux(io.sub,RvvOp.sub.U,RvvOp.add.U))
}

class RvvAluCarrySpec extends AnyFreeSpec with ChiselSim {
  "parallel carry preserves independent e8/e16/e32/e64 ADD/SUB and scalar broadcasts" in {
    simulate(new RvvAluCarryHarness) { d =>
      val rng=new Random(91)
      val full=(BigInt(1)<<512)-1
      val cases=Seq((full,BigInt(1)),(BigInt(0),full),
        (BigInt("ff00"*32,16),BigInt("00ff"*32,16)),
        (BigInt("7f"*64,16),BigInt("81"*64,16))) ++
        Seq.fill(8)((BigInt(512,rng),BigInt(512,rng)))
      for(sew <- 0 until 4; sub <- Seq(false,true); scalarOperand <- Seq(false,true); (a,b) <- cases) {
        val width=8 << sew; val mask=(BigInt(1)<<width)-1
        val scalar=b & ((BigInt(1)<<64)-1)
        val expected=(0 until 512/width).map { e =>
          val av=(a>>(width*e)) & mask
          val bv=if(scalarOperand) scalar & mask else (b>>(width*e)) & mask
          ((if(sub) av-bv else av+bv) & mask) << (width*e)
        }.foldLeft(BigInt(0))(_ | _)
        d.io.a.poke(a.U); d.io.b.poke(b.U); d.io.scalar.poke(scalar.U)
        d.io.scalarOperand.poke(scalarOperand.B); d.io.sew.poke(sew.U); d.io.sub.poke(sub.B)
        d.clock.step(); d.io.result.expect(expected.U)
      }
    }
  }
}
