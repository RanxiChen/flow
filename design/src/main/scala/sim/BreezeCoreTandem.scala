package flow.sim

final case class RawCommitEvent(
    valid: Boolean,
    pc: BigInt,
    inst: BigInt,
    nextPc: BigInt,
    estop: Boolean,
    rdWriteEn: Boolean,
    rdAddr: Int,
    rdData: BigInt,
    memEn: Boolean,
    memIsWrite: Boolean,
    memAddr: BigInt,
    memAlignedAddr: BigInt,
    memRData: BigInt,
    memWData: BigInt,
    memWMask: BigInt,
    rdPending: Boolean = false,
    rdIsFp: Boolean = false
)

final case class BreezeCoreSimTandemResult(
    result: BreezeCoreSimResult,
    commitEvents: Seq[RawCommitEvent],
    lateEvents: Seq[LateRegisterEvent] = Seq.empty
)

final case class LateRegisterEvent(cycle: Int, isFp: Boolean, rd: Int, data: BigInt, error: Boolean)

/** Protocol accounting only; arithmetic/ISA comparison is a separate task. */
final class PendingTrace {
    private val pending = scala.collection.mutable.Set.empty[(Boolean, Int)]
    def commit(event: RawCommitEvent): Unit = {
        val key = (event.rdIsFp, event.rdAddr)
        if (event.valid && (event.rdIsFp || event.rdAddr != 0)) {
            if (event.rdPending) {
                require(!pending(key), s"duplicate pending destination $key")
                pending += key
            } else if (event.rdWriteEn) {
                require(!pending(key), s"ordinary write overlaps pending destination $key")
            }
        }
    }
    def complete(event: LateRegisterEvent): Unit = {
        val key = (event.isFp, event.rd)
        require(pending(key), s"late completion without pending destination $key")
        pending -= key
    }
    def finish(): Unit = require(pending.isEmpty, s"unfinished pending destinations $pending")
}
