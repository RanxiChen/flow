package flow.sim

object CommitEffects {
    final case class RegWrite(addr: Int, data: BigInt)
    final case class MemRead(addr: BigInt, alignedAddr: BigInt, data: BigInt)
    final case class MemWrite(addr: BigInt, alignedAddr: BigInt, data: BigInt, mask: BigInt)
    final case class Redirect(fromPc: BigInt, nextPc: BigInt)
}

import CommitEffects._

final case class CommitUpdate(
    pc: BigInt,
    inst: BigInt,
    nextPc: BigInt,
    regWrite: Option[RegWrite],
    memRead: Option[MemRead],
    memWrite: Option[MemWrite],
    redirect: Option[Redirect],
    estop: Boolean
)

object RawCommitEventParser {
    def toCommitUpdate(event: RawCommitEvent): CommitUpdate = {
        val regWrite =
            if (event.rdWriteEn && event.rdAddr != 0) Some(RegWrite(event.rdAddr, event.rdData))
            else None

        val memRead =
            if (event.memEn && !event.memIsWrite) Some(MemRead(event.memAddr, event.memAlignedAddr, event.memRData))
            else None

        val memWrite =
            if (event.memEn && event.memIsWrite) {
                Some(MemWrite(event.memAddr, event.memAlignedAddr, event.memWData, event.memWMask))
            } else {
                None
            }

        val redirect =
            if (event.nextPc != (event.pc + 4)) Some(Redirect(event.pc, event.nextPc))
            else None

        CommitUpdate(
          pc = event.pc,
          inst = event.inst,
          nextPc = event.nextPc,
          regWrite = regWrite,
          memRead = memRead,
          memWrite = memWrite,
          redirect = redirect,
          estop = event.estop
        )
    }

    def toCommitUpdates(events: Seq[RawCommitEvent]): Seq[CommitUpdate] = {
        events.filter(_.valid).map(toCommitUpdate)
    }
}
