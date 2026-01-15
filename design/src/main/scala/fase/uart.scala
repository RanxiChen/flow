package fase

import chisel3._
import chisel3.util._


class Tx(freq_hz:Int, baudrate: Int) extends Module {
    /*8-N-1*/
    val io = IO(new Bundle{
        val txd = Output(Bool())
        val data = Flipped(DecoupledIO(UInt(8.W)))
    })
    val post_cycle = freq_hz/baudrate
    val post_cycle_counter = RegInit(0.U(log2Ceil(post_cycle).W))   
    io.txd := true.B
    //0 represent idle, 1 mean post
    val state = RegInit(false.B)
    io.data.ready :=  !state
    val post_index = RegInit(0xf.U(4.W))
    // 1.B ## data ## 0.B, totally 10 bits
    val post_data = RegInit(0.U(10.W))
    //state transition
    when(state === false.B){
        when(io.data.valid){
            post_data := true.B ## io.data.bits ## false.B
            post_index := 0.U
            state := true.B
            post_cycle_counter := 0.U
        }
    }.otherwise{
        when(post_index < 9.U){
            when(post_cycle_counter === (post_cycle-1).U){
                post_cycle_counter := 0.U
                post_index := post_index + 1.U                
            }.otherwise(
                post_cycle_counter := post_cycle_counter + 1.U
            )
            io.txd := post_data(post_index)
        }.elsewhen(post_index === 9.U){
            when(post_cycle_counter === (post_cycle-1).U){
                post_cycle_counter := 0x0.U
                state := false.B
                post_index := 0xf.U
            }.otherwise(
                post_cycle_counter := post_cycle_counter + 1.U
            )
            io.txd := true.B
        }.otherwise{
            state := false.B
            post_cycle_counter := 0.U
            post_index := 0xf.U
            io.txd := true.B
        }
    } 
}

class Rx(freq_hz:Int, baudrate: Int) extends Module {
    val io = IO(new Bundle{
        val rxd = Input(Bool())
        val data = DecoupledIO(UInt(8.W))
        val state = Output(Bool())
        val index = Output(UInt(4.W))
        val sample_cycle_counter = Output(UInt(log2Ceil(freq_hz/baudrate).W))
    })
    val sample_cycle = freq_hz/baudrate
    val sample_cycle_counter = RegInit(0.U(log2Ceil(sample_cycle).W))
    val sample_time = sample_cycle/2
    val rxd_data = RegInit(0.U(8.W))
    io.data.valid := false.B
    io.data.bits := 0.U
    val sample_rxd_data = RegInit(false.B)
    val state = RegInit(false.B)
    val index = RegInit(0.U(4.W))
    //sub state 
    when(state){
        sample_cycle_counter := sample_cycle_counter + 1.U
        when(sample_cycle_counter === (sample_time-1).U){
            //sample
            sample_rxd_data := io.rxd
        }.elsewhen(sample_cycle_counter === (sample_cycle -1).U){
            sample_cycle_counter := 0.U
            when(index === 0.U){
                when(sample_rxd_data === false.B){
                    //really start bit
                    index := index + 1.U
                }.otherwise{
                    state := false.B
                    index := 0.U
                }
            }.elsewhen(index <= 8.U){
                index := index + 1.U
                // 使用按位设置而非 OR 操作，避免旧数据残留
                rxd_data := rxd_data | (sample_rxd_data.asUInt << (index-1.U))
            }.elsewhen(index === 9.U){
                //detect stop bit
                //whatever clear to idle
                state := false.B
                index := 0.U
                when(sample_rxd_data === true.B){
                    //really stop
                    //make data valid
                    io.data.bits := rxd_data
                    io.data.valid := true.B
                    when(io.data.fire){
                        //data accepted
                        rxd_data := 0.U
                        state := false.B
                        index := 0.U
                        sample_cycle_counter := 0.U
                    }
                }
            }.otherwise{
                state := false.B
                index := 0.U
            }
        }
    }.elsewhen(state === false.B){
        //how invoke receive
        when(io.rxd === false.B){
            state := true.B
            sample_cycle_counter := 0.U
            index := 0.U        // 确保 index 重置
            rxd_data := 0.U     // 关键修复：清空接收缓冲区
        }
    }
    io.state := state
    io.index := index
    io.sample_cycle_counter := sample_cycle_counter
}