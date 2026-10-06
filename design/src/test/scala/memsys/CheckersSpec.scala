package flow.memsys

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** The checkers must catch what they claim to catch (pure Scala, no simulator). */
class CheckersSpec extends AnyFreeSpec with Matchers {
  private def mem = new GoldenMemory(32, l => l * 7)

  "GoldenMemory keeps versions and masked writes" in {
    val g = mem
    g.current(5) mustBe 35
    g.writeMasked(5, BigInt(0xab), BigInt(1))
    g.current(5) mustBe ((BigInt(35) & ~BigInt(0xff)) | 0xab)
    g.seenSince(5, 0, 35) mustBe true
    g.seenSince(5, 1, 35) mustBe false
  }

  "SwmrMonitor rejects two owners, owner+sharer and stale copies" in {
    val g = mem; val m = new SwmrMonitor(g)
    m.check(0, Seq((0, 1, LState.M, 7), (1, 2, LState.S, 14)))
    an[AssertionError] must be thrownBy m.check(1, Seq((0, 1, LState.M, 7), (1, 1, LState.E, 7)))
    an[AssertionError] must be thrownBy m.check(2, Seq((0, 1, LState.E, 7), (1, 1, LState.S, 7)))
    an[AssertionError] must be thrownBy m.check(3, Seq((0, 1, LState.S, 8)))
    m.check(4, Seq((0, 1, LState.I, 99), (1, 1, LState.S, 7), (2, 1, LState.S, 7)))
  }

  "Watchdog reports stuck transactions and global stalls" in {
    val w = new Watchdog(limit = 10, idleLimit = 5)
    w.start("a", 0); w.progress(0); w.tick(4, "")
    an[AssertionError] must be thrownBy w.tick(6, "dump")
    val w2 = new Watchdog(limit = 10, idleLimit = 100)
    w2.start("b", 0); w2.progress(10)
    an[AssertionError] must be thrownBy w2.tick(11, "")
    w2.finish("b"); w2.tick(50, "")
  }
}
