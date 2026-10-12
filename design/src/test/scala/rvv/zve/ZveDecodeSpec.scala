package flow.rvv.zve

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class ZveDecodeHarness(p: ZveParams) extends Module {
  val io=IO(new Bundle { val in=Input(new ZveIssue(p)); val out=Output(new ZveCommand(p)) })
  io.out := ZveDecode(io.in,p)
}
class ZveDecodeSpec extends AnyFreeSpec with ChiselSim {
  "independent EEW/EMUL, FPR scalar capture and rejected FP64/overlap encodings" in {
    simulate(new ZveDecodeHarness(ZveParams())) { d =>
      d.io.in.scalar.rs1.poke(123.U); d.io.in.scalar.rs2.poke(8.U); d.io.in.scalar.vstart.poke(0.U)
      d.io.in.scalar.vxrm.poke(0.U); d.io.in.scalar.frm.poke(0.U); d.io.in.scalar.rd.poke(0.U)
      d.io.in.fpScalar.poke(BigInt("ffffffff3f800000",16).U)
      def instruction(name: String,vd: Int=16,vs2: Int=8,vs1: Int=12,vm: Boolean=true): BigInt = {
        val bits=ZveEncodings.forms.find(_._1 == name).get._3
        val fields=BigInt(vd)<<7 | BigInt(vs1)<<15 | BigInt(vs2)<<20 | BigInt(if(vm) 1 else 0)<<25
        bits.value | (fields & ~bits.mask & BigInt("ffffffff",16))
      }
      d.io.in.scalar.vtype.poke(18.U); d.io.in.scalar.vl.poke(64.U) // e32,m4
      d.io.in.scalar.instruction.poke(instruction("vadd.vv").U)
      d.io.out.legal.expect(true.B); d.io.out.source2Registers.expect(4.U); d.io.out.destinationRegisters.expect(4.U)
      d.io.out.reads.expect(BigInt("000fff00",16).U); d.io.out.writes.expect(BigInt("000f0000",16).U)
      d.io.in.scalar.instruction.poke(instruction("vfadd.vf").U)
      d.io.out.legal.expect(true.B); d.io.out.vectorSource1.expect(false.B)
      d.io.out.issue.fpScalar.expect(BigInt("ffffffff3f800000",16).U)
      d.io.in.scalar.vtype.poke(9.U); d.io.in.scalar.vl.poke(64.U) // e16,m2
      d.io.in.scalar.instruction.poke(instruction("vfwcvt.f.x.v",16,8).U)
      d.io.out.legal.expect(true.B); d.io.out.source2Width.expect(1.U); d.io.out.destinationWidth.expect(2.U)
      d.io.out.source2Registers.expect(2.U); d.io.out.destinationRegisters.expect(4.U)
      d.io.in.scalar.instruction.poke(instruction("vfncvt.x.f.w",16,8).U)
      d.io.out.legal.expect(true.B); d.io.out.source2Width.expect(2.U); d.io.out.destinationWidth.expect(1.U)
      d.io.out.source2Registers.expect(4.U); d.io.out.destinationRegisters.expect(2.U)
      d.io.in.scalar.vtype.poke(24.U); d.io.in.scalar.vl.poke(8.U) // e64 is outside this profile
      d.io.in.scalar.instruction.poke(instruction("vfadd.vv").U); d.io.out.legal.expect(false.B)
      d.io.in.scalar.vtype.poke(16.U); d.io.in.scalar.vl.poke(16.U)
      d.io.in.scalar.instruction.poke(instruction("vrgather.vv",8,8,12).U); d.io.out.legal.expect(false.B)
      d.io.in.scalar.instruction.poke(instruction("vadd.vv",0,8,12,false).U); d.io.out.legal.expect(false.B)
      d.io.in.scalar.instruction.poke(instruction("vmseq.vv",0,8,12,false).U); d.io.out.legal.expect(true.B)
      d.io.in.scalar.instruction.poke(0xffffffffL.U); d.io.out.legal.expect(false.B)
      d.io.in.scalar.instruction.poke(instruction("vadd.vv").U)
      d.io.in.scalar.vtype.poke(6.U); d.io.in.scalar.vl.poke(16.U) // e8,mf4
      d.io.out.legal.expect(true.B); d.io.out.source2Registers.expect(1.U); d.io.out.source2Bytes.expect(16.U)
      d.io.in.scalar.vtype.poke(14.U); d.io.in.scalar.vl.poke(8.U) // e16,mf4 reserved for ELEN=32
      d.io.out.legal.expect(false.B)
      d.io.in.scalar.vtype.poke(7.U); d.io.in.scalar.vl.poke(8.U) // mf8 reserved for ELEN=32
      d.io.out.legal.expect(false.B)
    }
  }
}
