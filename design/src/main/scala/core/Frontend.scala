package core

import chisel3._
import chisel3.util._
import top.DefaultConfig._
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
    val sucessful_fetch = WireDefault(false.B)
    val pc = RegInit(BOOT_ADDR.U(64.W))
    val next_pc = Wire(UInt(64.W))
    when(io.ctrl.pc_misfetch){
        next_pc := io.ctrl.pc_redir
    }.otherwise{
        next_pc := pc + 4.U
    }

    when(sucessful_fetch){
        pc := next_pc
    }
    io.memreq.req_addr := pc
    sucessful_fetch := io.memreq.can_next && io.memreq.resp_addr === pc
    io.fetch.bits.data := io.memreq.data
    io.fetch.bits.pc := pc
    io.fetch.valid := sucessful_fetch 
    when(io.fetch.fire){
        printf(cf"[FE] Fetch inst 0x${Hexadecimal(io.fetch.bits.data)} at PC=0x${Hexadecimal(io.fetch.bits.pc)}\n")
    }
}