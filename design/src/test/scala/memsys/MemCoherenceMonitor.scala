package flow.memsys

import chisel3._
import flow.coherence._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import MemTestKit._

/** Test-side coherence monitor (coherence-l2-rtl-spec 1.2–1.5). It rebuilds
  * each core's line state from the link handshakes alone: 'S' for shared,
  * 'X' for E/M (E→M is silent; `hasData` reveals M). A permission is added
  * only at the grant handshake and removed at the probe answer or Put
  * handshake, so the link view always covers what the L1D physically holds
  * and link-level SWMR implies physical SWMR.
  *
  * Within a cycle RSP↓ is processed before RSP↑: the L2 registers answers
  * before it can grant, so a grant that only becomes legal through an
  * answer in the same cycle is reported.
  */
class CoherenceMonitor(obs: Vec[CohLinkObs], val logDepth: Int = 32, allowErrors: Boolean = false) extends CycleAgent {
  val name = "coherence-monitor"
  val n: Int = obs.size
  private val held = Array.fill(n)(mutable.Map.empty[BigInt, Char])
  private val getOut = Array.fill(n)(Option.empty[(Boolean, BigInt)])
  private val putOut = Array.fill(n)(Option.empty[BigInt])
  private val probeOut = Array.fill(n)(Option.empty[(Boolean, Boolean, BigInt)])
  private val log = mutable.Queue.empty[String]
  /** (cycle, core, "DataS"|"DataE"|"AckE", line). */
  val grants = ArrayBuffer.empty[(Long, Int, String, BigInt)]
  /** (cycle, core, "Inv"|"Down", line, owner). */
  val probes = ArrayBuffer.empty[(Long, Int, String, BigInt, Boolean)]
  /** (cycle, core, "Put"|"InvAck"|"DownAck", line, hasData). */
  val answers = ArrayBuffer.empty[(Long, Int, String, BigInt, Boolean)]
  /** (cycle, core, "GetS"|"GetM", line) at the REQ handshake. */
  val reqs = ArrayBuffer.empty[(Long, Int, String, BigInt)]
  /** (cycle, core, op, line) of grants that carried error=1 (allowErrors only). */
  val errorGrants = ArrayBuffer.empty[(Long, Int, String, BigInt)]

  def state(core: Int, line: BigInt): Char = held(core).getOrElse(line, 'I')
  def holders(line: BigInt): Seq[(Int, Char)] = (0 until n).map(c => (c, state(c, line))).filter(_._2 != 'I')
  def probesTo(core: Int, line: BigInt): Seq[String] = probes.filter(p => p._2 == core && p._4 == line).map(_._3).toSeq
  def grantsTo(core: Int, line: BigInt): Seq[String] = grants.filter(g => g._2 == core && g._4 == line).map(_._3).toSeq

  private def note(s: String): Unit = { log.enqueue(s"@$now $s"); while (log.size > logDepth) log.dequeue() }
  private def fail(core: Int, line: BigInt, msg: String): Nothing =
    throw new AssertionError(s"[$name @ cycle $now] core $core line ${hex(line << 5)}: $msg\n" +
      s"holders: ${holders(line).mkString(", ")}\nrecent messages:\n  " + log.mkString("\n  "))
  private def ensure(cond: Boolean, core: Int, line: BigInt, msg: => String): Unit = if (!cond) fail(core, line, msg)
  private def othersIdle(core: Int, line: BigInt): Boolean = (0 until n).forall(c => c == core || state(c, line) == 'I')
  private def noOtherOwner(core: Int, line: BigInt): Boolean = (0 until n).forall(c => c == core || state(c, line) != 'X')

  private val downNames = Seq(RspDownOp.DataS -> "DataS", RspDownOp.DataE -> "DataE", RspDownOp.AckE -> "AckE",
    RspDownOp.PutAck -> "PutAck", RspDownOp.ReadData -> "ReadData", RspDownOp.WriteAck -> "WriteAck")
  private def downName(v: BigInt): String = downNames.find(_._1.litValue == v).map(_._2).getOrElse(s"op$v")

  protected def drive(): Unit = ()

  def sample(): Unit = {
    val o = obs
    // RSP↓: grants and PutAck.
    for (c <- 0 until n if o(c).rspDownFire.peek().litToBoolean) {
      val op = downName(o(c).rspDown.op.peek().litValue)
      val err = o(c).rspDown.error.peek().litToBoolean
      if (op == "PutAck") {
        val l = putOut(c).getOrElse(fail(c, -1, "PutAck without an outstanding Put"))
        note(s"core $c PutAck ${hex(l << 5)}")
        putOut(c) = None
      } else {
        val (isGetM, l) = getOut(c).getOrElse(fail(c, -1, s"$op without an outstanding Get"))
        note(s"core $c $op ${hex(l << 5)}${if (err) " error" else ""}")
        ensure(!err || allowErrors, c, l, s"$op carries a refill error (none injected in multi-core tests)")
        if (err) {
          // l1d-rtl-spec 6.2: an error refill is never installed (tag-only
          // clean-up to I), so it grants no permission.
          ensure(op == "DataS" || op == "DataE", c, l, s"$op with error=1")
          ensure(state(c, l) != 'X', c, l, s"error $op while the core holds E/M")
          held(c) -= l
          errorGrants += ((now, c, op, l))
        } else op match {
          case "DataS" =>
            ensure(!isGetM, c, l, "GetM granted DataS")
            ensure(state(c, l) == 'I', c, l, s"DataS while the core holds ${state(c, l)}")
            ensure(noOtherOwner(c, l), c, l, "DataS while another core holds E/M (SWMR)")
            held(c)(l) = 'S'
          case "DataE" =>
            ensure(state(c, l) == 'I', c, l, s"DataE while the core holds ${state(c, l)}")
            ensure(othersIdle(c, l), c, l, "DataE while another core holds the line (SWMR)")
            held(c)(l) = 'X'
          case "AckE" =>
            ensure(isGetM, c, l, "GetS granted AckE")
            ensure(state(c, l) == 'S', c, l, s"AckE while the core holds ${state(c, l)} (an Inv removed its copy)")
            ensure(othersIdle(c, l), c, l, "AckE while another core holds the line (SWMR)")
            held(c)(l) = 'X'
          case other => fail(c, l, s"L1D received $other")
        }
        if (!err) grants += ((now, c, op, l))
        getOut(c) = None
      }
    }
    // RSP↑: Put and probe answers.
    for (c <- 0 until n if o(c).rspUpFire.peek().litToBoolean) {
      val u = o(c).rspUp
      val opv = u.op.peek().litValue
      val hasData = u.hasData.peek().litToBoolean
      val l = u.addr.peek().litValue
      val st = state(c, l)
      if (opv == RspUpOp.Put.litValue) {
        note(s"core $c Put ${hex(l << 5)} data=$hasData (was $st)")
        ensure(putOut(c).isEmpty, c, l, s"second Put while ${putOut(c).map(x => hex(x << 5))} is unacknowledged")
        ensure(st != 'I', c, l, "Put of a line the core does not hold")
        ensure(!hasData || st == 'X', c, l, "dirty Put from a sharer")
        held(c) -= l
        putOut(c) = Some(l)
        answers += ((now, c, "Put", l, hasData))
      } else {
        val inv = opv == RspUpOp.InvAck.litValue
        ensure(inv || opv == RspUpOp.DownAck.litValue, c, l, s"RSPup op $opv")
        val ans = if (inv) "InvAck" else "DownAck"
        note(s"core $c $ans ${hex(l << 5)} data=$hasData (was $st)")
        val (pInv, pOwner, pl) = probeOut(c).getOrElse(fail(c, l, s"$ans without an outstanding probe"))
        ensure(pl == l, c, l, s"$ans for ${hex(l << 5)}, probe was ${hex(pl << 5)}")
        ensure(inv == pInv, c, l, s"$ans answers a ${if (pInv) "Inv" else "Down"}")
        ensure(!hasData || st == 'X', c, l, s"$ans with data from state $st")
        ensure(!hasData || pOwner, c, l, s"$ans with data for a probe with owner=0")
        if (inv) held(c) -= l else if (st == 'X') held(c)(l) = 'S'
        probeOut(c) = None
        answers += ((now, c, ans, l, hasData))
      }
    }
    // SNP.
    for (c <- 0 until n if o(c).snpFire.peek().litToBoolean) {
      val s = o(c).snp
      val inv = s.op.peek().litValue == SnpOp.Inv.litValue
      val owner = s.owner.peek().litToBoolean
      val l = s.addr.peek().litValue
      note(s"core $c ${if (inv) "Inv" else "Down"} ${hex(l << 5)} owner=$owner (holds ${state(c, l)})")
      ensure(probeOut(c).isEmpty, c, l, "second probe before the first was answered")
      ensure(inv || owner, c, l, "Down with owner=0")
      probeOut(c) = Some((inv, owner, l))
      probes += ((now, c, if (inv) "Inv" else "Down", l, owner))
    }
    // REQ.
    for (c <- 0 until n if o(c).reqFire.peek().litToBoolean) {
      val q = o(c).req
      val opv = q.op.peek().litValue
      val l = q.addr.peek().litValue
      val isGetM = opv == ReqOp.GetM.litValue
      ensure(isGetM || opv == ReqOp.GetS.litValue, c, l, s"L1D sent REQ op $opv")
      note(s"core $c ${if (isGetM) "GetM" else "GetS"} ${hex(l << 5)} (holds ${state(c, l)})")
      ensure(getOut(c).isEmpty, c, l, s"second Get while ${getOut(c).map(x => hex(x._2 << 5))} is outstanding")
      ensure(!putOut(c).contains(l), c, l, "Get while its Put is unacknowledged")
      val st = state(c, l)
      ensure(if (isGetM) st != 'X' else st == 'I', c, l, s"${if (isGetM) "GetM" else "GetS"} from state $st")
      getOut(c) = Some((isGetM, l))
      reqs += ((now, c, if (isGetM) "GetM" else "GetS", l))
    }
  }

  def idle: Boolean = (0 until n).forall(c => getOut(c).isEmpty && putOut(c).isEmpty && probeOut(c).isEmpty)
  override def describe: String =
    s"$name: " + (0 until n).map(c => s"core $c get ${getOut(c).map(x => hex(x._2 << 5))} " +
      s"put ${putOut(c).map(x => hex(x << 5))} probe ${probeOut(c).map(x => hex(x._3 << 5))} held ${held(c).size}")
      .mkString("; ") + "\nrecent messages:\n  " + log.mkString("\n  ")
}
