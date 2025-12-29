package core

import chisel3._
import chisel3.util._
import top.DefaultConfig._
import firtoolresolver.shaded.coursier.util.Sync
import chisel3.util.experimental.loadMemoryFromFileInline
class InstPack extends Bundle {
    val data = UInt(32.W)
    val pc   = UInt(64.W)
}

class FrontendCtrl extends Bundle {
    val flush = Bool()
    val pc_misfetch = Bool()
    val pc_redir = UInt(64.W)
}

class IMemPort extends Bundle {
    val req_addr = Output(UInt(64.W))
    val resp_addr = Input(UInt(64.W))
    val data = Input(UInt(32.W))
    val can_next = Input(Bool())
}

class Frontend extends Module {
    val io = IO(new Bundle {
        val fetch = Decoupled(new InstPack)
        val memreq = new IMemPort
        val ctrl = Flipped(new FrontendCtrl)
    })
    // at this stage, we will not get data from memory
    io.memreq <> DontCare
    io.memreq.req_addr := 0.U
    val pc = RegInit(START_ADDR.U(64.W))
    val update_pc = WireDefault(false.B)
    when(io.ctrl.flush){
        pc := io.ctrl.pc_redir
    }.elsewhen(update_pc){
        pc := pc + 4.U
    }
    val itcm = SyncReadMem(128, UInt(32.W))
    loadMemoryFromFileInline(itcm,"itcm_init.hex")
    val inst = itcm.read(pc)
}