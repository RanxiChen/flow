package flow.core

import chisel3._
import chisel3.util._
import flow.interface._

/** Performance state, updated at commit or from qualified per-cycle events.
  * The HPM storage trails the architectural count by one pending increment.
  * Exposing storage + pending preserves the original cycle-by-cycle CSR view.
  */
class BreezePerformanceCounters(xlen: Int = 64, val numHpm: Int = 8) extends Module {
    require(numHpm > 0 && numHpm <= 29)
    private val eventWidth = log2Ceil(BREEZE_HPM_EVENT.LOAD_USE_STALL + 1)
    val io = IO(new Bundle {
        // Already qualified by commit_valid, write_en, and absence of a trap.
        val write = Input(Bool())
        val address = Input(UInt(12.W))
        val data = Input(UInt(xlen.W))
        val retire = Input(Bool())
        val events = Input(new BreezeHpmEvents)
        val coreinst = Output(UInt(xlen.W))
        val mcycle = Output(UInt(xlen.W))
        val minstret = Output(UInt(xlen.W))
        val inhibit = Output(UInt(32.W))
        val counter = Output(Vec(numHpm, UInt(xlen.W)))
        val selector = Output(Vec(numHpm, UInt(eventWidth.W)))
    })

    val coreinst = RegInit(0.U(xlen.W))
    val mcycle = RegInit(0.U(xlen.W))
    val minstret = RegInit(0.U(xlen.W))
    val inhibit = RegInit(0.U(32.W))
    val counters = RegInit(VecInit(Seq.fill(numHpm)(0.U(xlen.W))))
    val selectors = RegInit(VecInit(Seq.fill(numHpm)(0.U(eventWidth.W))))
    val pending = RegInit(VecInit(Seq.fill(numHpm)(false.B)))

    def writes(address: Int): Bool = io.write && io.address === address.U(12.W)
    when(io.retire) { coreinst := coreinst + 1.U }
    when(writes(CSRMAP.mcycle)) {
        mcycle := io.data
    }.elsewhen(!inhibit(0)) { mcycle := mcycle + 1.U }
    when(writes(CSRMAP.minstret)) {
        minstret := io.data
    }.elsewhen(io.retire && !inhibit(2)) { minstret := minstret + 1.U }
    private val inhibitMask = (BigInt(1) << 0) | (BigInt(1) << 2) |
        (((BigInt(1) << numHpm) - 1) << 3)
    when(writes(CSRMAP.mcountinhibit)) { inhibit := io.data(31, 0) & inhibitMask.U(32.W) }

    // Validate the FULL software value before truncating. For example, 0x101
    // must still select NONE, not event 1.
    val legalSelector = Mux(io.data <= BREEZE_HPM_EVENT.LOAD_USE_STALL.U,
        io.data(eventWidth - 1, 0), 0.U(eventWidth.W))
    val eventTable = Seq(
        BREEZE_HPM_EVENT.CONTROL_RETIRED -> io.events.controlRetired,
        BREEZE_HPM_EVENT.CONTROL_TAKEN -> io.events.controlTaken,
        BREEZE_HPM_EVENT.PREDICTION_MISS -> io.events.predictionMiss,
        BREEZE_HPM_EVENT.ICACHE_ACCESS -> io.events.icacheAccess,
        BREEZE_HPM_EVENT.ICACHE_MISS -> io.events.icacheMiss,
        BREEZE_HPM_EVENT.DCACHE_ACCESS -> io.events.dcacheAccess,
        BREEZE_HPM_EVENT.DCACHE_MISS -> io.events.dcacheMiss,
        BREEZE_HPM_EVENT.DCACHE_UNCACHED -> io.events.dcacheUncached,
        BREEZE_HPM_EVENT.MEM_STALL_CYCLE -> io.events.memStallCycle,
        BREEZE_HPM_EVENT.LOAD_USE_STALL -> io.events.loadUseStall
    )
    for (index <- 0 until numHpm) {
        when(writes(CSRMAP.mhpmevent3 + index)) { selectors(index) := legalSelector }
        val selectedEvent = Mux1H(eventTable.map { case (id, event) =>
            (selectors(index) === id.U(eventWidth.W)) -> event
        })
        val visible = counters(index) + pending(index).asUInt
        io.counter(index) := visible
        when(writes(CSRMAP.mhpmcounter3 + index)) {
            counters(index) := io.data
            pending(index) := false.B
        }.otherwise {
            counters(index) := visible
            // Capture the decision using the OLD selector/inhibit on a CSR
            // configuration write, exactly as the unpipelined counter did.
            pending(index) := !inhibit(index + 3) && selectedEvent
        }
    }
    io.coreinst := coreinst
    io.mcycle := mcycle
    io.minstret := minstret
    io.inhibit := inhibit
    io.selector := selectors
}
