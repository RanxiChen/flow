package flow.sim

sealed trait TandemLogMode

object TandemLogMode {
    case object Off extends TandemLogMode
    case object RawCommit extends TandemLogMode
}

object RawCommitEventLogFormatter {
    private def hex(value: BigInt): String = s"0x${value.toString(16)}"

    def format(cycle: Int, event: RawCommitEvent): String = {
        val parts = scala.collection.mutable.ArrayBuffer[String](
            "[COMMIT]",
            s"cycle=$cycle",
            s"pc=${hex(event.pc)}",
            s"inst=${hex(event.inst)}",
            s"next=${hex(event.nextPc)}"
        )

        if (event.memEn && !event.memIsWrite) {
            parts += s"bus-read[addr=${hex(event.memAlignedAddr)} data64=${hex(event.memRData)}]"
        }

        if (event.memEn && event.memIsWrite) {
            parts += s"bus-write[addr=${hex(event.memAlignedAddr)} data=${hex(event.memWData)} mask=${hex(event.memWMask)}]"
        }

        if (event.rdWriteEn && event.rdAddr != 0) {
            parts += s"reg-write[x${event.rdAddr}=${hex(event.rdData)}]"
        }

        if (event.nextPc != (event.pc + 4)) {
            parts += s"redirect[to=${hex(event.nextPc)}]"
        }

        if (event.estop) {
            parts += "estop"
        }

        parts.mkString(" ")
    }
}
