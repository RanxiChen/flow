package core

import chisel3._
import chisel3.util._

class in_order_core(val useFASE: Boolean=false) extends CoreModule{
    val instbuf = Module(new InstBuffer())
    val be = Module(new Backend())
}