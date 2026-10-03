package flow.mmu.sv39

import chisel3._
import scala.collection.mutable
import scala.util.Random

/** Sparse physical memory, with one outstanding PTE read and configurable latency. */
object Sv39TestSupport {
  val Read = 0x43; val Rwx = 0xcf
  val Root = BigInt(0x100); val Middle = BigInt(0x200); val Bottom = BigInt(0x300)
  def pte(ppn: BigInt, flags: Int = Read): BigInt = (ppn << 10) | flags
  def pteAddress(base: BigInt, va: BigInt, level: Int): BigInt =
    (base << 12) + (((va >> (12 + 9 * level)) & 511) << 3)
  case class Result(kind: String, pa: BigInt)
  def permission(flags: Int, cmd: Int, priv: Int, mprv: Boolean, mpp: Int, sum: Boolean, mxr: Boolean): Boolean = {
    def bit(n: Int) = (flags & (1 << n)) != 0
    val effective = if (cmd != 0 && mprv) mpp else priv
    effective == 3 || (bit(6) && (cmd match {
      case 0 => bit(3)
      case 1 => bit(1) || (mxr && bit(3))
      case 2 => bit(2) && bit(7)
    }) && !(effective == 0 && !bit(4)) && !(effective == 1 && bit(4) && (cmd == 0 || !sum)))
  }
  /** Independent iterative reference walker; it never reads DUT arrays or walk-cache state. */
  def reference(memory: collection.Map[BigInt, BigInt], faults: collection.Set[BigInt], va: BigInt,
    root: BigInt, cmd: Int, priv: Int, mprv: Boolean = false, mpp: Int = 1,
    sum: Boolean = false, mxr: Boolean = false, sv39: Boolean = true): Result = {
    val eff = if (cmd != 0 && mprv) mpp else priv
    if (!sv39 || eff == 3) return Result("hit", va)
    val upper = va >> 38
    if (upper != 0 && upper != ((BigInt(1) << 26) - 1)) return Result("pageFault", 0)
    var base = root
    for (level <- 2 to 0 by -1) {
      val addr = pteAddress(base, va, level)
      if (faults(addr)) return Result("accessFault", 0)
      val bits = memory.getOrElse(addr, BigInt(0))
      val flags = (bits & 255).toInt; val ppn = (bits >> 10) & ((BigInt(1) << 44) - 1)
      if ((flags & 1) == 0 || ((flags & 6) == 4) || (bits >> 54) != 0) return Result("pageFault", 0)
      if ((flags & 10) != 0) {
        val low = (BigInt(1) << (9 * level)) - 1
        if ((ppn & low) != 0 || !permission(flags, cmd, priv, mprv, mpp, sum, mxr)) return Result("pageFault", 0)
        val offset = (BigInt(1) << (12 + 9 * level)) - 1
        return Result("hit", ((ppn << 12) & ~offset) | (va & offset))
      }
      base = ppn
    }
    Result("pageFault", 0)
  }

  class Driver(val d: Sv39Mmu, var latency: Int = 1, seed: Int = 1) {
    val memory = mutable.Map.empty[BigInt, BigInt]
    val faults = mutable.Set.empty[BigInt]
    val reads = mutable.ArrayBuffer.empty[(Int, BigInt)]
    val rng = new Random(seed)
    var backpressure = false
    var cycle = 0
    private var response: Option[(Int, BigInt, Boolean)] = None
    d.io.csr.sv39.poke(true.B); d.io.csr.asid.poke(0.U); d.io.csr.rootPpn.poke(Root.U)
    d.io.csr.priv.poke(1.U); d.io.csr.mprv.poke(false.B); d.io.csr.mpp.poke(1.U)
    d.io.csr.sum.poke(false.B); d.io.csr.mxr.poke(false.B)
    d.io.sfence.valid.poke(false.B); d.io.sfence.rs1Nz.poke(false.B); d.io.sfence.rs2Nz.poke(false.B)
    d.io.sfence.vaddr.poke(0.U); d.io.sfence.asid.poke(0.U)
    for (p <- Seq(d.io.itlb, d.io.dtlb)) {
      p.req.valid.poke(false.B); p.req.bits.vaddr.poke(0.U); p.kill.poke(false.B)
    }
    d.io.itlb.req.bits.cmd.poke(MmuCmd.Fetch); d.io.dtlb.req.bits.cmd.poke(MmuCmd.Load)
    d.io.ptwMem.req.ready.poke(true.B); d.io.ptwMem.resp.valid.poke(false.B)
    d.io.ptwMem.resp.bits.data.poke(0.U); d.io.ptwMem.resp.bits.accessFault.poke(false.B)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
    def tick(n: Int = 1): Unit = for (_ <- 0 until n) {
      d.io.ptwMem.req.ready.poke((!backpressure || rng.nextInt(4) == 0).B)
      val due = response.filter(_._1 == cycle)
      d.io.ptwMem.resp.valid.poke(due.nonEmpty.B)
      due.foreach { case (_, data, af) =>
        d.io.ptwMem.resp.bits.data.poke(data.U); d.io.ptwMem.resp.bits.accessFault.poke(af.B)
      }
      if (due.nonEmpty) response = None
      if (d.io.ptwMem.req.valid.peek().litToBoolean && d.io.ptwMem.req.ready.peek().litToBoolean) {
        assert(response.isEmpty, "multiple outstanding PTW reads")
        val addr = d.io.ptwMem.req.bits.paddr.peek().litValue
        assert((addr & 7) == 0)
        reads += ((cycle, addr))
        response = Some((cycle + latency, memory.getOrElse(addr, BigInt(0)), faults(addr)))
      }
      d.clock.step(); cycle += 1
    }
    def port(cmd: Int): TlbPortIO = if (cmd == 0) d.io.itlb else d.io.dtlb
    def setRequest(va: BigInt, cmd: Int): TlbPortIO = {
      val p = port(cmd); p.req.bits.vaddr.poke(va.U)
      p.req.bits.cmd.poke(cmd match { case 0 => MmuCmd.Fetch; case 1 => MmuCmd.Load; case 2 => MmuCmd.Store })
      p.req.valid.poke(true.B); p
    }
    def result(p: TlbPortIO): Result = {
      p.resp.valid.expect(true.B)
      val flags = Seq("hit" -> p.resp.bits.hit, "miss" -> p.resp.bits.miss,
        "pageFault" -> p.resp.bits.pageFault, "accessFault" -> p.resp.bits.accessFault)
      val on = flags.filter(_._2.peek().litToBoolean)
      assert(on.size == 1)
      Result(on.head._1, p.resp.bits.paddr.peek().litValue)
    }
    def request(va: BigInt, cmd: Int = 1): Result = {
      val p = port(cmd); p.req.ready.expect(true.B); setRequest(va, cmd)
      tick(); p.req.valid.poke(false.B)
      val r = result(p); tick(); r
    }
    def waitReady(cmd: Int = 1): Unit = {
      var timeout = 0
      while (!port(cmd).req.ready.peek().litToBoolean && timeout < 1000) { tick(); timeout += 1 }
      assert(timeout < 1000, "PTW failed to make progress")
    }
    def access(va: BigInt, cmd: Int = 1): Result = {
      val r = request(va, cmd)
      if (r.kind != "miss") r else { waitReady(cmd); request(va, cmd) }
    }
    def map4k(va: BigInt, ppn: BigInt, flags: Int = Read, root: BigInt = Root,
      middle: BigInt = Middle, bottom: BigInt = Bottom): Unit = {
      memory(pteAddress(root, va, 2)) = pte(middle, 1)
      memory(pteAddress(middle, va, 1)) = pte(bottom, 1)
      memory(pteAddress(bottom, va, 0)) = pte(ppn, flags)
    }
    def fence(va: BigInt = 0, rs1Nz: Boolean = false, rs2Nz: Boolean = false, asid: Int = 0): Unit = {
      d.io.idle.expect(true.B)
      d.io.sfence.vaddr.poke(va.U); d.io.sfence.rs1Nz.poke(rs1Nz.B)
      d.io.sfence.rs2Nz.poke(rs2Nz.B); d.io.sfence.asid.poke(asid.U); d.io.sfence.valid.poke(true.B)
      d.io.itlb.req.ready.expect(false.B); d.io.dtlb.req.ready.expect(false.B)
      tick(); d.io.sfence.valid.poke(false.B)
      if (rs1Nz) { d.io.idle.expect(false.B); d.io.dtlb.req.ready.expect(false.B); tick() }
      d.io.idle.expect(true.B)
    }
  }
}
