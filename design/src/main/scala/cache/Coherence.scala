package flow.cache

import chisel3._
import chisel3.util._

/** Opcodes of L1D -> Home coherence requests.
  *
  * GetInstr is the unified-L2 instruction refill request: the I$ never joins
  * MESI, so Home answers it with a grant carrying data but must not set any
  * D$ sharer bit. PutS/PutM carry `hasData` (PutM always does).
  */
object BreezeCoherenceOpcode extends ChiselEnum {
    val GetS, GetM, PutS, PutM, GetInstr = Value
}

/** Directory grant states returned to a requesting L1D. */
object BreezeGrantState extends ChiselEnum {
    val S, E, M = Value
}

/** Probe opcodes issued by Home to an L1D. */
object BreezeProbeOpcode extends ChiselEnum {
    val ProbeInv, ProbeToS, ProbeRecallInv = Value
}

/** Home directory line states. These are NOT the L1D MESI states.
  *
  * NONE   : no L1D holds a coherent copy; L2 data is the latest (an untracked
  *          I$ copy may exist).
  * SHARED : one or more L1Ds hold S; L2 data is the latest.
  * UNIQUE : exactly one L1D owner holds E or M; owner data is authoritative,
  *          L2 data may be stale (E may have silently upgraded to M).
  */
object BreezeDirectoryState extends ChiselEnum {
    val NONE, SHARED, UNIQUE = Value
}

/** L1D -> Home coherence request channel. Direction is from the L1D side.
  *
  * Backpressure is ready/valid. Payloads must stay stable under backpressure.
  * `txnId` is carried even though the first version serializes the global
  * coherence transaction, so stale responses can be asserted against.
  */
class BreezeCoherenceReqIO(
    val plen: Int = 32,
    val lineBytes: Int = 32,
    val hartIdWidth: Int = 1,
    val txnIdWidth: Int = 2
) extends Bundle {
    val valid = Output(Bool())
    val ready = Input(Bool())
    val opcode = Output(BreezeCoherenceOpcode())
    val srcHart = Output(UInt(hartIdWidth.W))
    val txnId = Output(UInt(txnIdWidth.W))
    val lineAddr = Output(UInt(plen.W))
    val hasData = Output(Bool())
    val lineData = Output(UInt((lineBytes * 8).W))
}

/** Home -> L1D grant/response channel. Direction is from the Home side.
  *
  * S->M upgrades may grant without data; miss grants always carry the full
  * line. `error` reports a failed refill/writeback and implies no install.
  */
class BreezeCoherenceGrantIO(
    val plen: Int = 32,
    val lineBytes: Int = 32,
    val hartIdWidth: Int = 1,
    val txnIdWidth: Int = 2
) extends Bundle {
    val valid = Output(Bool())
    val ready = Input(Bool())
    val dstHart = Output(UInt(hartIdWidth.W))
    val txnId = Output(UInt(txnIdWidth.W))
    val lineAddr = Output(UInt(plen.W))
    val grantState = Output(BreezeGrantState())
    val hasData = Output(Bool())
    val lineData = Output(UInt((lineBytes * 8).W))
    val error = Output(Bool())
}

/** Home -> L1D probe channel. Direction is from the Home side. */
class BreezeCoherenceProbeIO(
    val plen: Int = 32,
    val hartIdWidth: Int = 1,
    val txnIdWidth: Int = 2
) extends Bundle {
    val valid = Output(Bool())
    val ready = Input(Bool())
    val dstHart = Output(UInt(hartIdWidth.W))
    val txnId = Output(UInt(txnIdWidth.W))
    val lineAddr = Output(UInt(plen.W))
    val opcode = Output(BreezeProbeOpcode())
}

/** L1D -> Home probe response channel. Direction is from the L1D side.
  *
  * `ack` acknowledges the probe; `hasData`/`lineData` carry the owner's
  * current line when it must be recalled (ProbeToS on M, ProbeRecallInv).
  */
class BreezeCoherenceProbeRespIO(
    val plen: Int = 32,
    val lineBytes: Int = 32,
    val hartIdWidth: Int = 1,
    val txnIdWidth: Int = 2
) extends Bundle {
    val valid = Output(Bool())
    val ready = Input(Bool())
    val srcHart = Output(UInt(hartIdWidth.W))
    val txnId = Output(UInt(txnIdWidth.W))
    val lineAddr = Output(UInt(plen.W))
    val ack = Output(Bool())
    val hasData = Output(Bool())
    val lineData = Output(UInt((lineBytes * 8).W))
}
