package flow.frontend

import chisel3._
import chisel3.util._
import flow.interface._

class BreezePHT(val vlen: Int = 64, val ghrLength: Int = 8,
                val pcShift: Int = 2) extends Module {
    require(pcShift == 1 || pcShift == 2)
    require(vlen > pcShift)
    require(ghrLength > 0, "PHT ghrLength must be greater than 0")

    private val entryNum = 1 << ghrLength
    private val weaklyNotTaken = 1.U(2.W)

    val io = IO(new Bundle {
        val predict = Input(new BreezePHTPredictReq(vlen, ghrLength))
        val resp = Output(new BreezePHTPredictResp(ghrLength))
        val update = Input(new BreezePHTUpdateReq(ghrLength))
    })

    val table = RegInit(VecInit(Seq.fill(entryNum)(weaklyNotTaken)))

    val alignedPc = io.predict.pc(vlen - 1, pcShift)
    val pcIdx = alignedPc(ghrLength - 1, 0)
    val predictIdx = pcIdx ^ io.predict.ghr
    val predictCounter = table(predictIdx)

    io.resp.valid := io.predict.valid
    io.resp.idx := predictIdx
    io.resp.taken := io.predict.valid && predictCounter(1)

    when(io.update.valid) {
        val currCounter = table(io.update.idx)
        when(io.update.taken) {
            when(currCounter =/= 3.U) {
                table(io.update.idx) := currCounter + 1.U
            }
        }.otherwise {
            when(currCounter =/= 0.U) {
                table(io.update.idx) := currCounter - 1.U
            }
        }
    }
}
