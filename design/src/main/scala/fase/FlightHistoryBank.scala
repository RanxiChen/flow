package flow.fase

import chisel3._
import chisel3.util._

/** One passive event stream. Pages referenced by snapshots or recent history
  * cannot be allocated again. Overlapping snapshots share physical pages.
  */
class FlightHistoryBank(depth: Int, pageSize: Int, preEntries: Int,
                        postCycles: Int, snapshots: Int) extends Module {
  private val pages = depth / pageSize
  private val pw = log2Ceil(pages)
  private val ow = log2Ceil(pageSize)
  private val sw = log2Ceil(snapshots)
  private val recentPages = preEntries / pageSize + 1
  private val mapPages = (pageSize - 1 + preEntries + 1 + postCycles + pageSize - 1) / pageSize
  private val cw = log2Ceil(preEntries + postCycles + 2)
  require(isPow2(depth) && isPow2(pageSize) && pageSize >= 2)
  require(preEntries >= pageSize && preEntries % pageSize == 0)
  // Even disjoint, maximally sized snapshots leave a spare allocation page.
  require(pages >= snapshots * mapPages + recentPages + 1)
  val io = IO(new Bundle {
    val record = Input(Vec(8, UInt(64.W)))
    val write = Input(Bool())
    val capture = Input(Valid(UInt(sw.W)))
    val extend = Input(Vec(snapshots, Bool()))
    val clear = Input(Bool())
    val read = Input(Bool())
    val readSlot = Input(UInt(sw.W))
    val readEntry = Input(UInt(10.W))
    val data = Output(Vec(8, UInt(64.W)))
    val counts = Output(Vec(snapshots, UInt(cw.W)))
    val preCounts = Output(Vec(snapshots, UInt(cw.W)))
    val total = Output(UInt(64.W))
    val dropped = Output(UInt(64.W))
  })
  val ram = SyncReadMem(depth, Vec(8, UInt(64.W)))
  val current = RegInit(0.U(pw.W))
  val offset = RegInit(0.U(ow.W))
  val recent = RegInit(VecInit(Seq.fill(recentPages)(0.U(pw.W))))
  val recentUsed = RegInit(1.U(log2Ceil(recentPages + 1).W))
  val historyCount = RegInit(0.U(cw.W))
  val masks = RegInit(VecInit(Seq.fill(snapshots)(0.U(pages.W))))
  val maps = RegInit(VecInit(Seq.fill(snapshots)(VecInit(Seq.fill(mapPages)(0.U(pw.W))))))
  val starts = RegInit(VecInit(Seq.fill(snapshots)(0.U(ow.W))))
  val counts = RegInit(VecInit(Seq.fill(snapshots)(0.U(cw.W))))
  val preCounts = RegInit(VecInit(Seq.fill(snapshots)(0.U(cw.W))))
  val total = RegInit(0.U(64.W))
  val dropped = RegInit(0.U(64.W))
  def bit(page: UInt): UInt = UIntToOH(page, pages)
  val recentMask = (0 until recentPages).map(i => Mux(i.U < recentUsed, bit(recent(i)), 0.U(pages.W))).reduce(_ | _)
  val free = ~(masks.reduce(_ | _) | recentMask)
  val nextPage = PriorityEncoder(free)
  // This is recorder-local: even an invariant failure never stalls the CPU or
  // overwrites protected data. The loss counter makes such a failure visible.
  val write = io.write && !io.clear && free.orR
  when(io.write && !io.clear && !free.orR) { dropped := dropped + 1.U }
  assert(!io.write || io.clear || free.orR, "Flight recorder page reserve exhausted")
  when(write) {
    ram.write(Cat(current, offset), io.record)
    total := total + 1.U
    when(historyCount < preEntries.U) { historyCount := historyCount + 1.U }
    offset := offset + 1.U
    when(offset.andR) {
      current := nextPage
      recent(0) := nextPage
      for (i <- 1 until recentPages) recent(i) := recent(i-1)
      when(recentUsed < recentPages.U) { recentUsed := recentUsed + 1.U }
    }
  }
  val fullHistory = historyCount === preEntries.U
  val precedingPages = Mux(fullHistory, (recentPages-1).U, historyCount >> ow)
  val initialStart = Mux(fullHistory, offset, 0.U)
  val initialMask = (0 until recentPages).map(i => Mux(i.U <= precedingPages, bit(recent(i)), 0.U(pages.W))).reduce(_ | _)
  for (s <- 0 until snapshots) {
    when(io.extend(s) && write) {
      val linear = starts(s) +& counts(s)
      val mapIndex = (linear >> ow)(log2Ceil(mapPages)-1,0)
      assert(mapIndex < mapPages.U, "Flight snapshot page map overflow")
      maps(s)(mapIndex) := current
      masks(s) := masks(s) | bit(current)
      counts(s) := counts(s) + 1.U
    }
    // Capture wins when recycling a slot still in its post-fault window.
    when(io.capture.valid && io.capture.bits === s.U && !io.clear) {
      starts(s) := initialStart
      preCounts(s) := historyCount
      counts(s) := historyCount + write.asUInt
      masks(s) := initialMask
      for (m <- 0 until mapPages) {
        maps(s)(m) := 0.U
        if (m < recentPages) {
          val r = (precedingPages - m.U)(log2Ceil(recentPages)-1,0)
          when(m.U <= precedingPages) { maps(s)(m) := recent(r) }
        }
      }
    }
  }
  val readLinear = starts(io.readSlot) +& io.readEntry
  val readMap = (readLinear >> ow)(log2Ceil(mapPages)-1,0)
  val readPage = maps(io.readSlot)(readMap)
  io.data := ram.read(Cat(readPage, readLinear(ow-1,0)), io.read)
  io.counts := counts
  io.preCounts := preCounts
  io.total := total
  io.dropped := dropped
  when(io.clear) {
    current := 0.U; offset := 0.U; recent.foreach(_ := 0.U); recentUsed := 1.U
    historyCount := 0.U; masks.foreach(_ := 0.U)
    starts.foreach(_ := 0.U); counts.foreach(_ := 0.U); preCounts.foreach(_ := 0.U)
    total := 0.U; dropped := 0.U
  }
}
