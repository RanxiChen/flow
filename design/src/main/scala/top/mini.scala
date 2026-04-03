package flwo.top

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
/**
  * Mini配置将会使用axi接口，并且对外暴露两个axi总线，一个用来连接取值，一个用来连接数据
  * 总线将会使用axi的interconnet ip进行连接
  */

class MiniCoreTop extends Module {
    val io = IO(new Bundle{
        ???
    })
}