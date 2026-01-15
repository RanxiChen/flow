package wishbone

import chisel3._
import chisel3.util._
import dataclass.data
import fase._
/**
  * Litex wishbone port definition
  * now, just word addressed, and not accept arguments
  *
  * @param addr_width 
  * @param data_width
  */
class litex_wb_port extends Bundle {
    val adr_width = 32 - 2 // word addressed
    val sel_width = 4 // (32 / 8)
    val data_width = 32
    val burst = false
    val adr = Output(UInt(adr_width.W))
    val dat_w = Output(UInt(data_width.W))
    val dat_r = Input(UInt(data_width.W))
    val sel = Output(UInt(sel_width.W))
    val cyc = Output(Bool())
    val stb = Output(Bool())
    val ack = Input(Bool())
    val we = Output(Bool())
    val cti = Output(UInt(3.W))
    val bte = Output(UInt(2.W))
    val err = Input(Bool())
}


/**
  * One wishbone master circuit with uart rx/tx module
  * recieve addr from rx module,send data back to tx module
  * and every time read 32 bits data from 32-bits memory
  */
class uart_wb_master extends Module {
    val io = IO(new Bundle{
        val rxd = Input(Bool())
        val txd = Output(Bool())
        val wb = new litex_wb_port()
        val addr_buffer = Output(UInt(32.W))
        val data_buffer = Output(UInt(32.W))
        val state = Output(UInt(2.W))
    })
    object WbState extends ChiselEnum {
        val idle,recieve,wb_read,send = Value
    }
    import WbState._
    val state = RegInit(idle)
    val rx_module = Module(new Rx(50000000, 115200))
    val tx_module = Module(new Tx(50000000, 115200))
    rx_module.io.rxd := io.rxd
    io.txd := tx_module.io.txd
    rx_module.io.data.ready := false.B
    tx_module.io.data.valid := false.B
    tx_module.io.data.bits := 0xff.U
    val addr_buffer = RegInit(0.U(32.W))
    val data_buffer = RegInit(0.U(32.W))
    val buffer_length = RegInit(0.U(2.W)) //0~3,for one word(4 bytes)
    //default wishbone signals
    io.wb.adr := 0.U
    io.wb.dat_w := 0.U
    io.wb.sel := "b1111".U
    io.wb.cyc := false.B
    io.wb.stb := false.B
    io.wb.we := false.B
    io.wb.cti := "b000".U
    io.wb.bte := "b00".U
    //state machine
    when(state === idle){
        rx_module.io.data.ready := true.B
        when(rx_module.io.data.fire){
            addr_buffer := Cat(0.U(24.W),rx_module.io.data.bits)
            state := recieve
            buffer_length := 0.U
        }
    }.elsewhen(state === recieve){
        rx_module.io.data.ready := true.B
        when(rx_module.io.data.fire){
            addr_buffer := addr_buffer << 8 | rx_module.io.data.bits
            when(buffer_length === 3.U){
                //got full addr
                state := wb_read
                buffer_length := 0.U
            }.otherwise{
                buffer_length := buffer_length + 1.U
                state := recieve
            }
        }
    }.elsewhen(state === wb_read){
        //start wishbone read
        io.wb.adr := addr_buffer(31,2) //word addressed
        io.wb.cyc := true.B
        io.wb.stb := true.B
        when(io.wb.ack){
            data_buffer := io.wb.dat_r
            state := send
        }.otherwise{
            state := wb_read
        }
    }.elsewhen(state === send){
        //send data back byte by byte
        tx_module.io.data.valid := true.B
        tx_module.io.data.bits := data_buffer(7,0)
        when(tx_module.io.data.fire){
            data_buffer := data_buffer >> 8
            when(buffer_length === 3.U){
                state := idle
                buffer_length := 0.U
                data_buffer := 0.U
                addr_buffer := 0.U
            }.otherwise{
                buffer_length := buffer_length + 1.U
                state := send
            }
        }
    }
    io.addr_buffer := addr_buffer
    io.data_buffer := data_buffer
    io.state := state.asUInt
}

import _root_.circt.stage.ChiselStage

object WBMain extends App {
    println("Generating the WishBone-related hardware")
    args(0) match{
        case "1" => {
            println("Generating WishBone Read module")
            ChiselStage.emitSystemVerilogFile(
            new uart_wb_master,
            Array("--target-dir", "build"),
            firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
            )
        }
        case _ => {
            println("No such module")
        }
    }
}