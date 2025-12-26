package core

import chisel3._
import chisel3.util._

class in_order_core extends CoreModule{
    val fe = Module(new Frontend())
    val be = Module(new Backend())
    fe.io.fetch <> be.io.imem
    fe.io.ctrl <> be.io.fctl
    io.itcm <> fe.io.memreq
    be.io.dmem <> io.dtcm
}