package flow.backend

import chisel3._
import flow.fpu.BreezeFpChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** Independent instruction/result ledger. One elaboration covers all seeds.
  * The injected W2 writes target x31, outside the generated program's banks.
  * Progress is checked with the existing Environment watchdog, never enlarged.
  */
class BE2PerturbationSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  import Instructions._
  "G2 committed arithmetic and delayed late ownership under random W2 contention" in {
    simulate(new BE2W2StorageProbe(Some(7))) { d =>
      val seeds = sys.env.get("BE2_SEEDS").map(_.toInt).getOrElse(1000)
      for (seed <- 0 until seeds) {
        withClue(s"seed=$seed: ") {
          d.occupyW2.poke(false.B)
          val m = new Environment(d, seed); m.reset()
          m.enableFp()
          val rng = new scala.util.Random(seed)
          val a = 1 + rng.nextInt(127); val b = 1 + rng.nextInt(31)
          val pcs = Vector(m.issue(addi(20,0,a)), m.issue(addi(21,0,b)))
          for(_ <- 0 until 6) m.step()
          m.returnDelay = 1 + rng.nextInt(40)
          m.misses += 0x100
          m.values(0x200)=BigInt("3ff0000000000000",16)
          m.values(0x208)=BigInt("4000000000000000",16)
          val program = Vector(ld(10,0x100),ld(14,0x108)) ++ (1 to 8).map(rd => mdu(rd,20,21)) ++
            Vector(mdu(11,20,21,div=true), BigInt(0x0b), ld(1,0x200,fp=true), ld(2,0x208,fp=true),
              fp(3,1,2), addi(12,1,7), addi(13,10,1))
          var index = 0
          val start = m.pc
          var watchdog = 0
          while (index < program.size || m.events.count(_.kind == "commit") < ((start-BigInt(0x400))/4).toInt + program.size ||
              m.writes(13).isEmpty || m.writes(11).isEmpty || m.writes(3,true).isEmpty) {
            d.occupyW2.poke((rng.nextInt(3)==0).B)
            val offered = if(index < program.size) Some((start+index*4)->program(index)) else None
            if(m.step(offered)) index += 1
            watchdog += 1
            require(watchdog < 300, s"G2 deadlock seed=$seed")
          }
          d.occupyW2.poke(false.B)
          for (_ <- 0 until 8) m.step()
          m.events.filter(_.kind == "commit").map(_.pc).toVector mustBe
            ((0 until ((start-BigInt(0x400))/4).toInt).map(i=>BigInt(0x400)+i*4).toVector ++ program.indices.map(i => start+i*4))
          for(rd <- 1 to 8) m.writes(rd).map(_.data).toVector mustBe Vector(BigInt(a*b))
          m.writes(10).map(_.data).toVector mustBe Vector(BigInt(0x1234))
          m.writes(14).map(_.data).toVector mustBe Vector(BigInt(0x1234))
          m.writes(11).map(_.data).toVector mustBe Vector(BigInt(a/b))
          m.writes(12).map(_.data).toVector mustBe Vector(BigInt(a*b+7))
          m.writes(13).map(_.data).toVector mustBe Vector(BigInt(0x1235))
          m.writes(3,true).map(_.data).toVector mustBe Vector(BigInt("4008000000000000",16))
          val sets=m.events.filter(_.kind=="busySet").map(e=>(e.rd,e.data)).sorted
          val clears=m.events.filter(_.kind=="busyClear").map(e=>(e.rd,e.data)).sorted
          sets mustBe clears
          sets.distinct mustBe sets
          d.io.observe.memAdvance.expect(true.B); d.io.observe.permAdvance.expect(true.B)
          d.io.observe.gprBusy.expect(0.U)
        }
      }
    }
  }
}
