package fase

import chisel3._
import chisel3.util._
import dataclass.data

/**
 * module id: 1
  * Just use Tx module now
  * transport data from 0 to 0xff every second
  */
class SecTx(ctrl_clk_freq: Int,baudrate: Int) extends Module {
    val io = IO(new Bundle{
        val txd = Output(Bool())
    })
    val timer_counter = RegInit(0.U(log2Ceil(ctrl_clk_freq*10).W))
    val sec_pulse = Wire(Bool())
    when(timer_counter === (ctrl_clk_freq*10-1).U){
        timer_counter := 0.U
        sec_pulse := true.B
    }.otherwise{
        timer_counter := timer_counter + 1.U
        sec_pulse := false.B
    }
    val data_counter = RegInit(0.U(8.W))
    when(sec_pulse){
        data_counter := data_counter + 1.U
    }
    val tx_module = Module(new Tx(ctrl_clk_freq, baudrate))
    tx_module.io.data.bits := data_counter
    tx_module.io.data.valid := sec_pulse
    io.txd := tx_module.io.txd
}

class UartRx(freq_hz:Int, baudrate: Int) extends Module {
    val io = IO(new Bundle{
        val rxd = Input(Bool())
        val led = Output(UInt(8.W))
        val index = Output(UInt(4.W))
        val state = Output(Bool())
    })
    val rx_module = Module(new Rx(freq_hz, baudrate))
    rx_module.io.rxd := io.rxd
    io.led := 0.U
    val data_buffer = RegInit(0.U(8.W))
    rx_module.io.data.ready := true.B
    when(rx_module.io.data.fire){
        data_buffer := rx_module.io.data.bits
    }
    io.led := data_buffer
    io.index := rx_module.io.index
    io.state := rx_module.io.state
}

class UartLoop(freq_hz:Int, baudrate: Int) extends Module {
    val io = IO(new Bundle{
        val rxd = Input(Bool())
        val txd = Output(Bool())
        val rx_index = Output(UInt(4.W))
        val rx_state = Output(Bool())
        val counter = Output(UInt(log2Ceil(freq_hz/baudrate).W))
        val loop_state = Output(UInt(2.W))
        val data = Output(UInt(8.W))
    })
    val rx_module = Module(new Rx(freq_hz, baudrate))
    val tx_module = Module(new Tx(freq_hz, baudrate))
    object work_state extends ChiselEnum {
        val idle, receiving, store, sending = Value
    }
    import work_state._
    val state = RegInit(idle)
    rx_module.io.rxd := io.rxd
    io.txd := tx_module.io.txd
    rx_module.io.data.ready := false.B
    tx_module.io.data.valid := false.B
    tx_module.io.data.bits := 0xff.U
    val data_buffer = RegInit(0.U(8.W))

    when(state === idle){
        rx_module.io.data.ready := true.B
        tx_module.io.data.bits := 0.U
        tx_module.io.data.valid := false.B
        when(rx_module.io.data.fire){
            data_buffer := rx_module.io.data.bits
            state := store
        }
    }.elsewhen(state === store){
        rx_module.io.data.ready := false.B
        tx_module.io.data.valid := false.B
        state := sending
    }.elsewhen(state === sending){
        rx_module.io.data.ready := false.B
        tx_module.io.data.valid := true.B
        tx_module.io.data.bits := data_buffer
        when(tx_module.io.data.fire){
            state := idle
            data_buffer := 0.U
        }
    }.otherwise{
        rx_module.io.data.ready := false.B
        tx_module.io.data.valid := false.B
        state := idle
    }
    io.rx_index := rx_module.io.index
    io.rx_state := rx_module.io.state
    io.counter := rx_module.io.sample_cycle_counter
    io.loop_state := state.asUInt
    io.data := data_buffer
}


import _root_.circt.stage.ChiselStage

object UartMain extends App {
    println("Generating the Uart-related hardware")
    args(0) match{
        case "1" => {
            println("Generating SecTx module")
            ChiselStage.emitSystemVerilogFile(
            new SecTx(125000000, 115200),
            Array("--target-dir", "build"),
            firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
            )
        }
        case "2" => {
            println("Generating UartRx module")
            ChiselStage.emitSystemVerilogFile(
            new UartRx(125000000, 115200),
            Array("--target-dir", "build"),
            firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
            )
        }
        case "3" => {
            println("Generating UartLoop module")
            ChiselStage.emitSystemVerilogFile(
            new UartLoop(125000000, 115200),
            Array("--target-dir", "build"),
            firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
            )
        }
        case _ => {
            println("No such module")
        }
    }
    /*
    val dut_module = new SecTx(50000000, 115200)
    ChiselStage.emitSystemVerilogFile(
        dut_module,
        Array("--target-dir", "build"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )*/
}