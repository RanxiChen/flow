package flow.frontend

import chisel3._
import chisel3.util._

import flow.interface._

class BreezeBTBEntry(val vlen: Int) extends Bundle {
    val valid = Bool()
    val pcKey = UInt((vlen - 2).W)
    val target = UInt(vlen.W)
    val predType = FrontendPredType()
    val taken = Bool()
}

class BreezeBTB(val vlen: Int = 64, val entryNum: Int = 16) extends Module {
    require(entryNum > 0, "BTB entryNum must be greater than 0")
    require(vlen > 2, "BTB vlen must be greater than 2 for 32-bit aligned PC compare")

    val io = IO(new Bundle {
        val lookup = Input(new BreezeBTBLookupReq(vlen))
        val resp = Output(new BreezeBTBLookupResp(vlen))
        val update = Input(new BreezeBTBUpdateReq(vlen))
    })

    val entries = RegInit(VecInit(Seq.fill(entryNum)(0.U.asTypeOf(new BreezeBTBEntry(vlen)))))
    val rrPtr = RegInit(0.U(log2Ceil(entryNum).W))

    val lookupKey = io.lookup.pc(vlen - 1, 2)
    val matchVec = Wire(Vec(entryNum, Bool()))
    val hit = Wire(Bool())
    val hitIdx = Wire(UInt(log2Ceil(entryNum).W))

    for (i <- 0 until entryNum) {
        matchVec(i) := entries(i).valid && entries(i).pcKey === lookupKey
    }

    hit := matchVec.asUInt.orR
    hitIdx := PriorityEncoder(matchVec)

    io.resp.hit := hit
    io.resp.taken := false.B
    io.resp.predType := FrontendPredType.NONE
    io.resp.target := 0.U

    when(hit) {
        io.resp.taken := entries(hitIdx).taken
        io.resp.predType := entries(hitIdx).predType
        io.resp.target := entries(hitIdx).target
    }

    val updateKey = io.update.pc(vlen - 1, 2)
    val updateMatchVec = Wire(Vec(entryNum, Bool()))
    val emptyVec = Wire(Vec(entryNum, Bool()))
    val updateHit = Wire(Bool())
    val hasEmpty = Wire(Bool())
    val updateIdx = Wire(UInt(log2Ceil(entryNum).W))
    val emptyIdx = Wire(UInt(log2Ceil(entryNum).W))
    val allocIdx = Wire(UInt(log2Ceil(entryNum).W))

    for (i <- 0 until entryNum) {
        updateMatchVec(i) := entries(i).valid && entries(i).pcKey === updateKey
        emptyVec(i) := !entries(i).valid
    }

    updateHit := updateMatchVec.asUInt.orR
    hasEmpty := emptyVec.asUInt.orR
    updateIdx := PriorityEncoder(updateMatchVec)
    emptyIdx := PriorityEncoder(emptyVec)
    allocIdx := Mux(updateHit, updateIdx, Mux(hasEmpty, emptyIdx, rrPtr))

    when(io.update.valid) {
        entries(allocIdx).valid := true.B
        entries(allocIdx).pcKey := updateKey
        entries(allocIdx).target := io.update.target
        entries(allocIdx).predType := io.update.predType
        entries(allocIdx).taken := io.update.taken

        when(!updateHit && !hasEmpty) {
            rrPtr := Mux(rrPtr === (entryNum - 1).U, 0.U, rrPtr + 1.U)
        }
    }
}
