package flow.core

import chisel3._
import chisel3.util._
import flow.interface._

/** HPM raw events cross a register before selection/counting. Configuration
  * is captured alongside the event, preserving occurrence-time attribution.
  * HPM visibility trails events by one cycle; cycle/instret remain immediate.
  */
class BreezePerformanceCounters(xlen: Int = 64, val numHpm: Int = 8,
                                val supervisor: Boolean = true) extends Module {
    require(xlen == 64, "Breeze Sscofpmf currently implements RV64")
    require(numHpm > 0 && numHpm <= 29)
    private val eventWidth = log2Ceil(BREEZE_HPM_EVENT.MAX + 1)
    val io = IO(new Bundle {
        // Already qualified by commit_valid, write_en, and absence of a trap.
        val write = Input(Bool())
        val address = Input(UInt(12.W))
        val data = Input(UInt(xlen.W))
        val retire = Input(Bool())
        val privilege = Input(UInt(2.W))
        val events = Input(new BreezeHpmEvents)
        val observeControl = Output(UInt(64.W)); val observeIndex = Output(UInt(64.W))
        val observeData = Output(UInt(64.W)); val traceIndex = Output(UInt(64.W))
        val traceData = Output(UInt(64.W)); val traceStatus = Output(UInt(64.W))
        val coreinst = Output(UInt(xlen.W))
        val mcycle = Output(UInt(xlen.W))
        val minstret = Output(UInt(xlen.W))
        val inhibit = Output(UInt(32.W))
        val counter = Output(Vec(numHpm, UInt(xlen.W)))
        val selector = Output(Vec(numHpm, UInt(xlen.W)))
        val overflow = Output(UInt(32.W))
        val overflowRequest = Output(Bool())
    })

    val coreinst = RegInit(0.U(xlen.W))
    val mcycle = RegInit(0.U(xlen.W))
    val minstret = RegInit(0.U(xlen.W))
    val inhibit = RegInit(0.U(32.W))
    val counters = RegInit(VecInit(Seq.fill(numHpm)(0.U(xlen.W))))
    val selectors = RegInit(VecInit(Seq.fill(numHpm)(0.U(eventWidth.W))))
    val filters = RegInit(VecInit(Seq.fill(numHpm)(0.U(3.W)))) // M,S,U inhibit
    val overflow = RegInit(VecInit(Seq.fill(numHpm)(false.B)))
    val pending = RegInit(VecInit(Seq.fill(numHpm)(0.U(32.W))))
    val rawEvents = RegNext(io.events, 0.U.asTypeOf(io.events))
    val eventSelectors = RegNext(selectors, 0.U.asTypeOf(selectors))
    val eventInhibit = RegNext(inhibit, 0.U)
    val eventFilters = RegNext(filters, 0.U.asTypeOf(filters))
    val eventPrivilege = RegNext(io.privilege, PRIV_MODE.M.U)
    val rawRetire = RegNext(io.retire, false.B)
    val overflowRequests = Wire(Vec(numHpm, Bool()))

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
    val requestedEvent = io.data(55, 0)
    val legalSelector = Mux((requestedEvent <= BREEZE_HPM_EVENT.REPLAY_COPRO.U ||
        (requestedEvent >= flow.observe.BreezeObserveEvent.rawBase.U && requestedEvent <= BREEZE_HPM_EVENT.MAX.U)),
        requestedEvent(eventWidth - 1, 0), 0.U(eventWidth.W))
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
    val replayEvents = (0 until 7).map(i => (BREEZE_HPM_EVENT.REPLAY_OTHER + i) -> rawEvents.replay(i))
    for (index <- 0 until numHpm) {
        val selectorWrite = writes(CSRMAP.mhpmevent3 + index)
        when(selectorWrite) {
            selectors(index) := legalSelector
            filters(index) := Cat(io.data(62), if (supervisor) io.data(61,60) else 0.U(2.W))
            overflow(index) := io.data(63)
        }
        val eventOverwritten = RegNext(writes(CSRMAP.mhpmcounter3 + index), false.B)
        val observeEvents = rawEvents.observation.delta.zipWithIndex.map { case (event, i) =>
            (flow.observe.BreezeObserveEvent.rawBase + i) -> event
        }
        val cycleRaw = flow.observe.BreezeObserveEvent.raw("cpu_cycles")
        val retireRaw = flow.observe.BreezeObserveEvent.raw("retired_instructions")
        val allEvents = eventTable ++ replayEvents ++ observeEvents.filterNot { case(id, _) =>
            id == cycleRaw || id == retireRaw
        } ++ Seq(cycleRaw -> 1.U(32.W), retireRaw -> rawRetire.asUInt)
        val selectedEvent = Mux1H(allEvents.map { case (id, event) =>
            (eventSelectors(index) === id.U(eventWidth.W)) -> event.asUInt
        })
        val fromCache = eventSelectors(index) >= BREEZE_HPM_EVENT.ICACHE_ACCESS.U &&
            eventSelectors(index) <= BREEZE_HPM_EVENT.DCACHE_UNCACHED.U
        val fromObservation = eventSelectors(index) >= flow.observe.BreezeObserveEvent.rawBase.U &&
            eventSelectors(index) =/= cycleRaw.U && eventSelectors(index) =/= retireRaw.U
        val sourcePrivilege = Mux(fromObservation, rawEvents.observationPrivilege,
            Mux(fromCache, rawEvents.cachePrivilege, eventPrivilege))
        val modeInhibited = MuxLookup(sourcePrivilege, true.B)(Seq(
            PRIV_MODE.M.U -> eventFilters(index)(2), PRIV_MODE.S.U -> eventFilters(index)(1),
            PRIV_MODE.U.U -> eventFilters(index)(0)))
        val visible = counters(index) + pending(index)
        io.counter(index) := visible
        val increment = Mux(!eventInhibit(index + 3) && !eventOverwritten && !modeInhibited,
            selectedEvent, 0.U)
        // pending is already part of the CSR-visible value. Detect overflow
        // of the NEXT visible hardware sum, not its later backing-register fold.
        val nextVisible = visible +& increment
        val hardwareOverflow = !writes(CSRMAP.mhpmcounter3 + index) && nextVisible(xlen)
        overflowRequests(index) := hardwareOverflow && !overflow(index)
        when(hardwareOverflow) { overflow(index) := true.B }
        when(writes(CSRMAP.mhpmcounter3 + index)) {
            counters(index) := io.data
            pending(index) := 0.U
        }.otherwise {
            counters(index) := visible
            // A counter write discards the preceding delayed increment and
            // the event at the write edge. Later events count normally.
            pending(index) := increment
        }
        io.selector(index) := Cat(overflow(index), filters(index), 0.U((60-eventWidth).W), selectors(index))
    }
    val bank = Module(new flow.observe.BreezeObserveBank)
    bank.io.events := rawEvents.observation
    bank.io.events("cpu_cycles") := 1.U
    bank.io.events("retired_instructions") := rawRetire.asUInt
    bank.io.trace := rawEvents.instructionTrace
    bank.io.write := io.write; bank.io.address := io.address; bank.io.data := io.data
    io.observeControl := bank.io.control; io.observeIndex := bank.io.index; io.observeData := bank.io.read
    io.traceIndex := bank.io.traceIndex; io.traceData := bank.io.traceRead; io.traceStatus := bank.io.traceStatus
    io.coreinst := coreinst
    io.mcycle := mcycle
    io.minstret := minstret
    io.inhibit := inhibit
    io.overflow := Cat(overflow.reverse) << 3
    io.overflowRequest := overflowRequests.asUInt.orR
}
