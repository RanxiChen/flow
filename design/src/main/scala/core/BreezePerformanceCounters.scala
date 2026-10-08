package flow.core

import chisel3._
import chisel3.util._
import flow.interface._

/** HPM raw events cross a register before selection/counting. Configuration
  * is captured alongside the event, preserving occurrence-time attribution.
  * HPM visibility trails events by one cycle; cycle/instret remain immediate.
  */
class BreezePerformanceCounters(xlen: Int = 64, val numHpm: Int = 8) extends Module {
    require(numHpm > 0 && numHpm <= 29)
    private val eventWidth = log2Ceil(BREEZE_HPM_EVENT.WB_PORT_CONFLICT + 1)
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
    val pending = RegInit(VecInit(Seq.fill(numHpm)(0.U(2.W))))
    val rawEvents = RegNext(io.events, 0.U.asTypeOf(io.events))
    val eventSelectors = RegNext(selectors, 0.U.asTypeOf(selectors))
    val eventInhibit = RegNext(inhibit, 0.U)

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
    val legalSelector = Mux(io.data <= BREEZE_HPM_EVENT.WB_PORT_CONFLICT.U,
        io.data(eventWidth - 1, 0), 0.U(eventWidth.W))
    val eventTable = Seq(
        BREEZE_HPM_EVENT.CONTROL_RETIRED -> rawEvents.controlRetired,
        BREEZE_HPM_EVENT.CONTROL_TAKEN -> rawEvents.controlTaken,
        BREEZE_HPM_EVENT.PREDICTION_MISS -> rawEvents.predictionMiss,
        BREEZE_HPM_EVENT.ICACHE_ACCESS -> rawEvents.icacheAccess,
        BREEZE_HPM_EVENT.ICACHE_MISS -> rawEvents.icacheMiss,
        BREEZE_HPM_EVENT.DCACHE_ACCESS -> rawEvents.dcacheAccess,
        BREEZE_HPM_EVENT.DCACHE_MISS -> rawEvents.dcacheMiss,
        BREEZE_HPM_EVENT.DCACHE_UNCACHED -> rawEvents.dcacheUncached,
        BREEZE_HPM_EVENT.MEM_STALL_CYCLE -> rawEvents.memStallCycle,
        BREEZE_HPM_EVENT.LOAD_USE_STALL -> rawEvents.loadUseStall,
        BREEZE_HPM_EVENT.MUL_SOURCE_STALL -> rawEvents.mulSourceStall,
        BREEZE_HPM_EVENT.DIV_SOURCE_STALL -> rawEvents.divSourceStall,
        BREEZE_HPM_EVENT.WB_PORT_CONFLICT -> rawEvents.wbPortConflict
    )
    for (index <- 0 until numHpm) {
        when(writes(CSRMAP.mhpmevent3 + index)) { selectors(index) := legalSelector }
        val eventOverwritten = RegNext(writes(CSRMAP.mhpmcounter3 + index), false.B)
        val selectedEvent = Mux1H(eventTable.map { case (id, event) =>
            (eventSelectors(index) === id.U(eventWidth.W)) -> event.asUInt
        })
        val visible = counters(index) + pending(index)
        io.counter(index) := visible
        when(writes(CSRMAP.mhpmcounter3 + index)) {
            counters(index) := io.data
            pending(index) := 0.U
        }.otherwise {
            counters(index) := visible
            // A counter write discards the preceding delayed increment and
            // the event at the write edge. Later events count normally.
            pending(index) := Mux(!eventInhibit(index + 3) && !eventOverwritten, selectedEvent, 0.U)
        }
    }
    io.coreinst := coreinst
    io.mcycle := mcycle
    io.minstret := minstret
    io.inhibit := inhibit
    io.selector := selectors
}
