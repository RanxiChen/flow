package flow.rvv.zve

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class ZveFpEstimateSpec extends AnyFreeSpec with ChiselSim {
  "ISA estimate examples and IEEE special values have the prescribed bits and flags" in {
    simulate(new ZveFpEstimate) { d =>
      def check(input: Long,root: Boolean,output: Long,flags: Int=0,rm: Int=0): Unit = {
        d.io.value.poke(BigInt(input).U); d.io.reciprocalSqrt.poke(root.B); d.io.rounding.poke(rm.U)
        d.clock.step(); d.io.result.expect(BigInt(output).U); d.io.flags.expect(flags.U)
      }
      // Exact examples from the frozen v1.0 ISA estimate tables.
      check(0x00718abcL,false,0x7e900000L); check(0x7f765432L,false,0x00214000L)
      check(0x00718abcL,true,0x5f080000L); check(0x7f765432L,true,0x1f820000L)
      for(root<-Seq(false,true)) {
        check(0L,root,0x7f800000L,8); check(0x80000000L,root,0xff800000L,8)
        check(0x7f800000L,root,0L); check(0x7fc00001L,root,0x7fc00000L)
        check(0x7f800001L,root,0x7fc00000L,16)
      }
      check(0xff800000L,false,0x80000000L); check(0xff800000L,true,0x7fc00000L,16)
      check(0xbf800000L,true,0x7fc00000L,16)
      for(rm<-0 until 5) {
        check(1L,false,if(rm==1 || rm==2)0x7f7fffffL else 0x7f800000L,5,rm)
        check(0x80000001L,false,if(rm==1 || rm==3)0xff7fffffL else 0xff800000L,5,rm)
      }
    }
  }
}
