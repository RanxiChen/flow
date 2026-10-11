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
          // Advance the stub PRNG by a seed-dependent number of real requests.
          // Resetting the DUT alone would otherwise repeat the same first delay
          // in all 1000 runs despite the other randomized perturbations.
          for (_ <- 0 until rng.nextInt(5)) {
            m.issue(BigInt(0x0b))
            for (_ <- 0 until 32) m.step()
          }
          val a = 1 + rng.nextInt(127); val b = 1 + rng.nextInt(31)
          val pcs = Vector(m.issue(addi(20,0,a)), m.issue(addi(21,0,b)))
          for(_ <- 0 until 6) m.step()
          m.returnDelay = 1 + rng.nextInt(40)
          m.misses += 0x100; m.misses += 0x140; m.misses += 0x180
          m.tlbMisses += 0x300; m.tlbDelay=5+rng.nextInt(20)
          m.mmioAddresses += 0x12001000; m.mmioDelay=1+rng.nextInt(25)
          m.issue((BigInt(0x12001)<<12)|(BigInt(22)<<7)|0x37)
          for(_ <- 0 until 6) m.step()
          m.values(0x200)=BigInt("3ff0000000000000",16)
          m.values(0x208)=BigInt("4000000000000000",16)
          val program = Vector(ld(18,0x300),ld(10,0x100),ld(14,0x108),ld(17,0x140),ld(16,0x180),
            (BigInt(22)<<15)|(BigInt(3)<<12)|(BigInt(19)<<7)|3) ++ (1 to 8).map(rd => mdu(rd,20,21)) ++
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
          for(rd <- Seq(16,17,18,19)) m.writes(rd).map(_.data).toVector mustBe Vector(BigInt(0x1234))
          m.events.count(_.kind=="mmioStart") mustBe 1
          m.events.filter(_.kind=="replay").map(_.data).toSet must contain allElementsOf Set(BigInt(1),BigInt(2),BigInt(3),BigInt(4),BigInt(6))
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
          sys.env.get("BE2_LEDGER_DIR").foreach { directory =>
            val root = os.Path(directory); os.makeDir.all(root)
            val memory = Seq(0x100,0x108,0x140,0x180,0x200,0x208,0x300,0x12001000)
            val ledger = ujson.Obj("seed" -> seed,
              "program" -> ujson.Arr.from(m.acceptedProgram.map { case (pc,inst) =>
                ujson.Obj("pc" -> pc.toString, "inst" -> inst.toString) }),
              "commits" -> ujson.Arr.from(m.events.filter(_.kind=="commit").map(_.pc.toString)),
              "writes" -> ujson.Arr.from(m.events.filter(e =>
                e.kind=="fpr" || (e.kind=="gpr" && e.rd!=31)).map(e =>
                ujson.Obj("bank" -> e.kind, "rd" -> e.rd, "data" -> e.data.toString))),
              "memory" -> ujson.Arr.from(memory.map(a =>
                ujson.Obj("address" -> a.toString, "data" -> m.values(BigInt(a)).toString))))
            os.write.over(root / s"seed-$seed.json", ujson.write(ledger))
          }
          // Timer perturbation at an empty precise boundary, with real CSR
          // masking/trap logic. The committed instruction ledger above is final.
          m.issue(addi(23,0,128)); m.issue(csr(0,0x304,23,1))
          m.issue(addi(23,0,8)); m.issue(csr(0,0x300,23,2))
          for(_ <- 0 until 6+rng.nextInt(8)) m.step()
          val redirects=m.events.count(_.kind=="redirect")
          m.timerInterrupt=true; m.step(); m.timerInterrupt=false
          for(_ <- 0 until 6) m.step()
          m.events.count(_.kind=="redirect") mustBe redirects+1
          m.events.filter(_.kind=="redirect").last.data mustBe BigInt(0x200)
        }
      }
    }
  }
}
