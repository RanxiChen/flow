package core

import chisel3._
import chisel3.util._
import top._
/**
  * Register File, used to store general purpose registers
  * @param XLEN
  */

class RegFileIO(XLEN:Int) extends Bundle{
    val rs1_addr = Input(UInt(5.W))
    val rs2_addr = Input(UInt(5.W))
    val rd_addr  = Input(UInt(5.W))
    val rd_data  = Input(UInt(XLEN.W))
    val rd_en    = Input(Bool())
    val rs1_data = Output(UInt(XLEN.W))
    val rs2_data = Output(UInt(XLEN.W))
}

class RegFile(XLEN:Int=64,val dumplog:Boolean=false) extends Module {
    val io = IO(new RegFileIO(XLEN))
    val content = RegInit(VecInit(Seq.fill(32)(0.U(XLEN.W))))
    io.rs1_data := Mux(io.rs1_addr === 0.U, 0.U, content(io.rs1_addr))
    io.rs2_data := Mux(io.rs2_addr === 0.U, 0.U, content(io.rs2_addr))
    when(io.rd_en && (io.rd_addr =/= 0.U)){
        content(io.rd_addr) := io.rd_data
    }        
    if(dumplog){
    printf(cf"[RegFile]\n")
    for(i <- 0 until 32){
        printf(cf"x[${i}%02d]=0x${content(i)}%x    ")
        if(i%4 == 3){
            printf("\n")
        }        
    }
    printf("\n")
    }
}

// Derived from Rocket Chip project (BSD 3-Clause License)
// Modified for this project
import scala.collection.mutable.ArrayBuffer
class RocketRegFile extends Module {
    val io = IO(new RegFileIO(64))
    val n = 31
    val w = 64
    val zero = false
    val rf = Mem(n, UInt(w.W))
  private def access(addr: UInt) = rf(~addr(log2Up(n)-1,0))
  private val reads = ArrayBuffer[(UInt,UInt)]()
  private var canRead = true
  def read(addr: UInt) = {
    require(canRead)
    reads += addr -> Wire(UInt())
    reads.last._2 := Mux(zero.B && addr === 0.U, 0.U, access(addr))
    reads.last._2
  }
  def write(addr: UInt, data: UInt) = {
    canRead = false
    when (addr =/= 0.U) {
      access(addr) := data
      for ((raddr, rdata) <- reads)
        when (addr === raddr) { rdata := data }
    }
  }
    io.rs1_data := read(io.rs1_addr)
    io.rs2_data := read(io.rs2_addr)
    when(io.rd_en){
        write(io.rd_addr, io.rd_data)
    }
}

class DiffRegFile extends Module {
    val io = IO(new Bundle{
        val sucess = Output(Bool())
        val index = Output(UInt(5.W))
        val reg1 = Output(UInt(64.W))
        val reg2 = Output(UInt(64.W))
    })
    io.sucess := false.B
    io.index := 0.U
    io.reg1 := 0.U
    io.reg2 := 0.U
    val a = Module(new RegFile(64))
    val b = Module(new RocketRegFile())
    val cnt = RegInit(0.U(5.W))
    val counter = RegInit(0.U(7.W))
    counter := counter + 1.U
    //first 32 cycle write
    when(counter < 32.U){
        a.io.rd_en := true.B
        b.io.rd_en := true.B
        a.io.rd_addr := counter
        b.io.rd_addr := counter
        a.io.rd_data := counter + 10.U
        b.io.rd_data := counter + 10.U
        a.io.rs1_addr := 0.U
        a.io.rs2_addr := 0.U
        b.io.rs1_addr := 0.U
        b.io.rs2_addr := 0.U
    }.otherwise{
        a.io.rd_en := false.B
        b.io.rd_en := false.B
        val read_addr = counter(4,0)
        a.io.rs1_addr := read_addr
        a.io.rs2_addr := read_addr + 1.U
        b.io.rs1_addr := read_addr
        b.io.rs2_addr := read_addr + 1.U
        a.io.rd_addr := 0.U
        a.io.rd_data := 0.U
        b.io.rd_addr := 0.U
        b.io.rd_data := 0.U
        when(a.io.rs1_data =/= b.io.rs1_data){
            io.sucess := false.B
            io.index := read_addr
            io.reg1 := a.io.rs1_data
            io.reg2 := b.io.rs1_data
        }.elsewhen(a.io.rs2_data =/= b.io.rs2_data){
            io.sucess := false.B
            io.index := read_addr + 1.U
            io.reg1 := a.io.rs2_data
            io.reg2 := b.io.rs2_data
        }.otherwise{
            io.sucess := true.B
            io.index := 0.U
            io.reg1 := 0.U
            io.reg2 := 0.U
        }
    }
}

import _root_.circt.stage.ChiselStage

object GenerateRegFileVerilogFile extends App {
    ChiselStage.emitSystemVerilogFile(
        new DiffRegFile(),
        Array("--target-dir", "build"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )
}

class CSRFile(XLEN:Int=64,val dumplog:Boolean=false) extends Module {
    val io = IO(new Bundle{
        val csr_addr = Input(UInt(12.W))
        val csr_cmd  = Input(UInt(CSR_CMD.width.W))
        val csr_reg_data = Input(UInt(XLEN.W))
        val csr_wdata = Output(UInt(XLEN.W))
        val rs1_id = Input(UInt(5.W))
        val rd_id = Input(UInt(5.W))
        //Privilege related
        val priv = Output(UInt(2.W))
        val core_retire = Input(Bool())
    })
    // csr supported
    val printer = RegInit(0.U(XLEN.W))
    val misa = RegInit(CoreParam.misa.U(64.W))
    val mvendorid = RegInit(0.U(32.W))
    val marchid = RegInit(0.U(XLEN.W))
    val mimpid = RegInit(0.U(XLEN.W))
    val mhartid = RegInit(0.U(XLEN.W)) // now just single core system
    //mstatus
    val __SIE = false.B
    val __MIE = RegInit(false.B)
    val __SPIE = false.B
    val __UBE = RegInit(false.B)
    val __MPIE = RegInit(false.B)
    val __SPP = RegInit(false.B)
    val __VS  = RegInit(0.U(2.W))
    val __MPP = RegInit(0.U(2.W))
    val __FS  = RegInit(0.U(2.W))
    val __XS  = RegInit(0.U(2.W))
    val __MPRV= RegInit(false.B)
    val __SUM = RegInit(false.B)
    val __MXR = RegInit(false.B)
    val __TVM = RegInit(false.B)
    val __TW  = RegInit(false.B)
    val __TSR = RegInit(false.B)
    val __SPELP= RegInit(false.B)
    val __SDT  = RegInit(false.B)
    val __UXL = RegInit(0.U(2.W))
    val __SXL = RegInit(0.U(2.W))
    val __SBE = RegInit(false.B)
    val __MBE = RegInit(false.B)
    val __GVA = RegInit(false.B)
    val __MPV = RegInit(false.B)
    val __MPELP = RegInit(false.B)
    val __MDT = RegInit(false.B)
    val __SD = RegInit(false.B) 
    //count retired instructions
    val retire_counter = RegInit(0.U(XLEN.W))
    if(dumplog){
        val inner_timer = RegInit(0.U(64.W))
        inner_timer := inner_timer + 1.U
        when(io.core_retire){
            retire_counter := retire_counter + 1.U
            printf(cf"[DEBUG] ${inner_timer}%0d core retired instruction count = ${retire_counter}%d\n")
        }
    }
    val csrFile = Seq(
        BitPat(CSRMAP.printer.U) -> printer,
        BitPat(CSRMAP.coreinst.U) -> retire_counter,
        BitPat(CSRMAP.misa.U)    -> misa,
        BitPat(CSRMAP.mvendorid.U)-> mvendorid,
        BitPat(CSRMAP.marchid.U)  -> marchid,
        BitPat(CSRMAP.mimpid.U)    -> mimpid,
        BitPat(CSRMAP.mhartid.U)  -> mhartid
    )
    val old_csr_val = WireDefault(0.U(XLEN.W))
    val new_csr_val = WireDefault(old_csr_val)
    val read_csr = Wire(Bool())
    val write_csr = Wire(Bool())
    val uimm = io.csr_reg_data(4,0)
    read_csr := false.B
    write_csr := false.B
    when(read_csr){
        old_csr_val := Lookup(io.csr_addr, 0.U, csrFile)
    }.otherwise{
        old_csr_val := 0.U
    }
    switch(io.csr_cmd){
        is(CSR_CMD.NOP.U){
            //nop
            read_csr := false.B
            write_csr := false.B
        }
        is(CSR_CMD.RW.U){
            read_csr := Mux(io.rd_id =/= 0.U, true.B, false.B)
            write_csr := true.B
            new_csr_val := io.csr_reg_data
            //printf(cf"[CSR] rw = 0x${io.csr_reg_data}%x\n")
        }
        is(CSR_CMD.RS.U){
            read_csr := true.B
            write_csr := Mux(io.rs1_id =/= 0.U, true.B, false.B)
            new_csr_val := old_csr_val | io.csr_reg_data
        }
        is(CSR_CMD.RC.U){
            read_csr := true.B
            write_csr := Mux(io.rs1_id =/= 0.U, true.B, false.B)
            new_csr_val := old_csr_val & (~io.csr_reg_data)
        }
        is(CSR_CMD.RWI.U){
           read_csr := Mux(io.rd_id =/= 0.U, true.B, false.B)
           write_csr := true.B
           new_csr_val := io.csr_reg_data 
        }
        is(CSR_CMD.RSI.U){
            read_csr := true.B
            write_csr := Mux(uimm =/= 0.U, true.B, false.B)
            new_csr_val := old_csr_val | io.csr_reg_data
        }
        is(CSR_CMD.RCI.U){
            read_csr := true.B
            write_csr := Mux(uimm =/= 0.U, true.B, false.B)
            new_csr_val := old_csr_val & (~io.csr_reg_data)
        }
    }
    when(write_csr){
        switch(io.csr_addr){
            is(CSRMAP.printer.U){
                printer := new_csr_val
                if(dumplog){
                    printf(cf"[INFO] printer = 0x${new_csr_val}%x\n")
                }
            }
            is(CSRMAP.coreinst.U){
                //retire_counter is read only
                if(dumplog){
                    printf(cf"[INFO] coreinst = 0x${retire_counter}%x\n")
                }
            }
            is(CSRMAP.misa.U){
                //currently misa is read only
                if(dumplog){
                    printf(cf"[INFO] misa = 0x${misa}%x\n")
                }
            }
            is(CSRMAP.mvendorid.U, CSRMAP.marchid.U, CSRMAP.mimpid.U){
                //currently mvendorid is read only
                if(dumplog){
                    printf(cf"[INFO] unimplemented csr\n")
                }
            }
            is(CSRMAP.mhartid.U){
                //currently mhartid is read only
                if(dumplog){
                    printf(cf"[INFO] mhartid = 0\n")
                }
            }
        }
    }
    io.csr_wdata := old_csr_val
    val priv = RegInit(PrivConst.MACHINE)
    io.priv := priv
    val csr_err_priv = WireDefault(false.B)
    csr_err_priv := ( io.csr_addr(9,8) > priv) || (io.csr_addr(11,10) === "b11".U && write_csr )

}