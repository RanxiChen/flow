package flow.rvv

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class RvvRegisterFileSpec extends AnyFreeSpec with ChiselSim {
  "two v0 subrows update their shadow bytes together and oldest writer wins a bank" in {
    val p=RvvParams(vlen=512,dlen=256,lanes=4,memoryBits=256)
    simulate(new RvvRegisterFile(p)) { d =>
      d.io.readValid.foreach(_.poke(false.B)); d.io.readRows.foreach(_.poke(0.U)); d.io.write.foreach(_.valid.poke(false.B))
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def write(w: Int,row: Int,data: BigInt,age: Int,enables: BigInt=(BigInt(1)<<32)-1): Unit = {
        d.io.write(w).valid.poke(true.B); d.io.write(w).bits.row.poke(row.U)
        d.io.write(w).bits.data.poke(data.U); d.io.write(w).bits.enables.poke(enables.U)
        d.io.age(w).poke(age.U)
      }
      val low=BigInt("11"*32,16); val high=BigInt("22"*32,16)
      write(0,0,low,1); write(1,1,high,2)
      d.io.write(0).ready.expect(true.B); d.io.write(1).ready.expect(true.B)
      d.clock.step(1); d.io.write.foreach(_.valid.poke(false.B)); d.clock.step(1)
      d.io.mask.expect((low | (high<<256)).U)
      d.io.readValid(0).poke(true.B); d.io.readRows(0).poke(0.U); d.clock.step(3); d.io.readData(0).expect(low.U)
      d.io.readRows(0).poke(1.U); d.clock.step(3); d.io.readData(0).expect(high.U); d.io.readValid(0).poke(false.B)
      val other=BigInt("33"*32,16)
      write(0,4,other,0); d.clock.step(1); d.io.write(0).valid.poke(false.B); d.clock.step(1)
      write(0,0,0x55,4,1); write(1,4,0x77,3,1) // same bank, different rows
      d.io.write(0).ready.expect(false.B); d.io.write(1).ready.expect(true.B)
      d.clock.step(1); d.io.write(1).valid.poke(false.B)
      d.io.mask.expect((low | (high<<256)).U)
      d.io.write(0).ready.expect(true.B); d.clock.step(1)
      d.io.write(0).valid.poke(false.B); d.clock.step(1)
      d.io.mask.expect(((low & ~BigInt(255)) | 0x55 | (high<<256)).U)
      d.io.readValid(0).poke(true.B); d.io.readRows(0).poke(4.U); d.clock.step(3); d.io.readData(0).expect(((other & ~BigInt(255)) | 0x77).U)
    }
  }
}
