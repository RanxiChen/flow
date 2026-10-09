package flow.frontend

import chisel3._
import chisel3.util._

import flow.interface._

class BreezeBTBEntry(val vlen: Int, val pcShift: Int) extends Bundle {
    val valid = Bool()
    val pcKey = UInt((vlen - pcShift).W)
    val target = UInt(vlen.W)
    val predType = FrontendPredType()
    val taken = Bool()
}

class BreezeBTB(val vlen: Int = 64, val entryNum: Int = 16,
                val pcShift: Int = 2) extends Module {
    require(entryNum > 0, "BTB entryNum must be greater than 0")
    require(pcShift == 1 || pcShift == 2)
    require(vlen > pcShift)

    val io = IO(new Bundle {
        val lookup = Input(new BreezeBTBLookupReq(vlen))
        val resp = Output(new BreezeBTBLookupResp(vlen))
        val update = Input(new BreezeBTBUpdateReq(vlen))
    })

    val entries = RegInit(VecInit(Seq.fill(entryNum)(
        0.U.asTypeOf(new BreezeBTBEntry(vlen, pcShift)))))
    val rrPtr = RegInit(0.U(log2Ceil(entryNum).W))

    val lookupKey = io.lookup.pc(vlen - 1, pcShift)
    val matchVec = Wire(Vec(entryNum, Bool()))
    val hit = Wire(Bool())

    for (i <- 0 until entryNum) {
        matchVec(i) := entries(i).valid && entries(i).pcKey === lookupKey
    }

    hit := matchVec.asUInt.orR
    // Preserve the old lowest-index winner, without encoding an index and
    // using it to select every target/type bit through a second mux tree.
    val lookupGrant = PriorityEncoderOH(matchVec.asUInt)
    val groups = (0 until entryNum).grouped(4).toSeq
    val targetParts = groups.map(ids => Mux1H(ids.map(i => lookupGrant(i)), ids.map(i => entries(i).target)))
    val typeParts = groups.map(ids => Mux1H(ids.map(i => lookupGrant(i)), ids.map(i => entries(i).predType.asUInt)))
    val takenParts = groups.map(ids => ids.map(i => lookupGrant(i) && entries(i).taken).reduce(_ || _))

    io.resp.hit := hit
    io.resp.taken := false.B
    io.resp.predType := FrontendPredType.NONE
    io.resp.target := 0.U

    when(hit) {
        io.resp.taken := takenParts.reduce(_ || _)
        io.resp.predType := typeParts.reduce(_ | _).asTypeOf(FrontendPredType())
        io.resp.target := targetParts.reduce(_ | _)
    }

    val updateKey = io.update.pc(vlen - 1, pcShift)
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
