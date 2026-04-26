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
    memWMask: BigInt
)

final case class BreezeCoreSimTandemResult(
    result: BreezeCoreSimResult,
    commitEvents: Seq[RawCommitEvent]
)
