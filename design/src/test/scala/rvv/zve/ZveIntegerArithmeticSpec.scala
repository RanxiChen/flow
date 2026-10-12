package flow.rvv.zve

import chisel3._
import org.scalatest.freespec.AnyFreeSpec
import chisel3.simulator.scalatest.ChiselSim

class ZveIntegerArithmeticHarness extends Module {
  val p=ZveParams()
  val io=IO(new Bundle {
    val operation=Input(UInt(9.W)); val sew=Input(UInt(2.W)); val sourceWide=Input(Bool())
    val destinationWide=Input(Bool()); val rounding=Input(UInt(2.W)); val carry=Input(Bool())
    val a=Input(UInt(64.W)); val b=Input(UInt(64.W)); val old=Input(UInt(64.W))
    val result=Output(UInt(64.W)); val saturated=Output(Bool())
  })
  val d=WireDefault(0.U.asTypeOf(new ZveCommand(p)))
  d.operation := io.operation; d.sew := io.sew; d.source2Width := io.sew+io.sourceWide.asUInt
  d.destinationWidth := io.sew+io.destinationWide.asUInt; d.issue.scalar.vxrm := io.rounding
  val (a,b)=ZveIntegerArithmetic.multiplyOperands(d,io.a,io.b,io.old)
  val productWide=RegNext((a*b).asUInt)
  val product=productWide(63,0)
  val (v,s)=ZveIntegerArithmetic(d,io.a,io.b,io.old,product,io.carry)
  io.result := v; io.saturated := s
}
class ZveIntegerArithmeticSpec extends AnyFreeSpec with ChiselSim {
  "integer element boundaries, high products and four fixed-point rounding modes" in {
    simulate(new ZveIntegerArithmeticHarness) { d =>
      d.io.sourceWide.poke(false.B); d.io.destinationWide.poke(false.B); d.io.rounding.poke(0.U)
      d.io.carry.poke(false.B); d.io.old.poke(0.U); d.io.a.poke(0.U); d.io.b.poke(0.U)
      d.io.operation.poke(ZveOp.vadd.U); d.io.sew.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def check(op: Int,w: Int,a: BigInt,b: BigInt,expected: BigInt,sat: Boolean=false): Unit = {
        val mask=(BigInt(1)<<w)-1
        d.io.operation.poke(op.U); d.io.sew.poke((if(w==8) 0 else if(w==16) 1 else 2).U)
        d.io.a.poke((a & ((BigInt(1)<<64)-1)).U); d.io.b.poke((b & ((BigInt(1)<<64)-1)).U)
        d.clock.step(2)
        assert((d.io.result.peek().litValue & mask)==(expected & mask),s"op=$op w=$w a=$a b=$b")
        d.io.saturated.expect(sat.B)
      }
      for(w<-Seq(8,16,32)) {
        val mask=(BigInt(1)<<w)-1; val max=(BigInt(1)<<(w-1))-1; val min = -(BigInt(1)<<(w-1))
        check(ZveOp.vadd,w,mask,1,0)
        check(ZveOp.vsub,w,0,1,mask)
        check(ZveOp.vmulhu,w,mask,mask,(mask*mask)>>w)
        check(ZveOp.vmulh,w,min,max,(min*max)>>w)
        check(ZveOp.vmulhsu,w,min,mask,(min*mask)>>w)
        check(ZveOp.vsadd,w,max,1,max,true)
        check(ZveOp.vssub,w,min,1,min,true)
        check(ZveOp.vsaddu,w,mask,1,mask,true)
        check(ZveOp.vssubu,w,0,1,0,true)
        check(ZveOp.vsmul,w,min,min,max,true)
        for(rm<-0 until 4) {
          d.io.rounding.poke(rm.U)
          val expected=Seq(2,2,1,1)(rm) // 3 >> 1, half-way with odd retained LSB.
          check(ZveOp.vssrl,w,3,1,expected)
          val negative=Seq(-1,-2,-2,-1)(rm) // -3 / 2 in two's-complement rounding.
          check(ZveOp.vssra,w,-3,1,negative)
        }
        d.io.rounding.poke(0.U)
      }
    }
  }
  "iterative division retains results through backpressure including div0 and signed overflow" in {
    simulate(new ZveDivider) { d =>
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def divide(a: Long,b: Long,signed: Boolean,rem: Boolean,width: Int,expected: Long): Unit = {
        d.io.in.bits.a.poke((a & 0xffffffffL).U); d.io.in.bits.b.poke((b & 0xffffffffL).U)
        d.io.in.bits.signed.poke(signed.B); d.io.in.bits.remainder.poke(rem.B); d.io.in.bits.sew.poke(width.U)
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
        var cycles=0
        while(!d.io.out.valid.peek().litToBoolean && cycles<40) { d.clock.step(); cycles+=1 }
        assert(cycles<40)
        val mask=if(width==0) 0xffL else if(width==1) 0xffffL else 0xffffffffL
        assert((d.io.out.bits.peek().litValue & mask)==BigInt(expected & mask))
        val value=d.io.out.bits.peek().litValue; d.clock.step(3); d.io.out.bits.expect(value.U)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
      divide(-7,3,true,false,2,-2); divide(-7,3,true,true,2,-1)
      divide(0x80000000L,0xffffffffL,true,false,2,0x80000000L)
      divide(37,0,false,false,0,255); divide(37,0,false,true,0,37)
      divide(65535,257,false,false,1,255)
    }
  }
}
