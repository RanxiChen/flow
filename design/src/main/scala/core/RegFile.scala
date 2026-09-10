package flow.core

import chisel3._
import chisel3.util._
import flow.config.{BreezePmpConfig, PrivilegeProfile}
import flow.interface._
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
    // Asynchronous reads and one synchronous write infer FPGA distributed RAM.
    // Reset only the validity bits: resetting every data bit prevents RAM
    // inference. Unwritten registers still read zero, including after reset.
    val content = Mem(32, UInt(XLEN.W))
    val initialized = RegInit(VecInit(Seq.fill(32)(false.B)))
    def stored(addr: UInt): UInt = Mux(initialized(addr), content(addr), 0.U)
    val writeValid = io.rd_en && (io.rd_addr =/= 0.U)
    val rs1WriteHit = writeValid && (io.rs1_addr === io.rd_addr)
    val rs2WriteHit = writeValid && (io.rs2_addr === io.rd_addr)

    // WB writes and decode reads happen in the same cycle. Make that ordering
    // explicit so a consumer sees the value being committed, not the previous
    // contents of the register file.
    io.rs1_data := Mux(io.rs1_addr === 0.U, 0.U,
        Mux(rs1WriteHit, io.rd_data, stored(io.rs1_addr)))
    io.rs2_data := Mux(io.rs2_addr === 0.U, 0.U,
        Mux(rs2WriteHit, io.rd_data, stored(io.rs2_addr)))
    when(writeValid && !reset.asBool){
        content(io.rd_addr) := io.rd_data
        initialized(io.rd_addr) := true.B
    }        
    if(dumplog){
    printf(cf"[RegFile]\n")
    for(i <- 0 until 32){
        printf(cf"x[${i}%02d]=0x${stored(i.U(5.W))}%x    ")
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

class CSRFile(XLEN:Int=64,val dumplog:Boolean=false, val enabledebug:Boolean=false,
              val hartId:Int=0,
              val privilegeProfile: PrivilegeProfile = PrivilegeProfile.Mcu,
              val enableCompressed: Boolean = false) extends Module {
    require(hartId >= 0, "CSRFile hartId must be non-negative")
    val io = IO(new Bundle{
        val csr_addr = Input(UInt(12.W))
        val csr_cmd  = Input(UInt(CSR_CMD.width.W))
        val csr_reg_data = Input(UInt(XLEN.W))
        val csr_old_data = Output(UInt(XLEN.W))
        val csr_new_data = Output(UInt(XLEN.W))
        val csr_write_en = Output(Bool())
        val rs1_id = Input(UInt(5.W))
        val rd_id = Input(UInt(5.W))
        val commit_valid = Input(Bool())
        val commit_addr = Input(UInt(12.W))
        val commit_wdata = Input(UInt(XLEN.W))
        val commit_write_en = Input(Bool())
        // Floating-point architectural state is updated only at the common
        // WB commit point. fp_flags are ordered NV,DZ,OF,UF,NX.
        val fp_commit_valid = Input(Bool())
        val fp_flags = Input(UInt(5.W))
        val frm = Output(UInt(3.W))
        val fp_enabled = Output(Bool())
        val retire_valid = Input(Bool())
        val hpmEvents = Input(new BreezeHpmEvents)
        val machineTimerInterrupt = Input(Bool())
        val machineSoftwareInterrupt = Input(Bool())
        val machineExternalInterrupt = Input(Bool())
        val supervisorExternalInterrupt = Input(Bool())
        val time = Input(UInt(XLEN.W))
        val trap = Input(new CSRTrapInfo(XLEN))
        val mret_commit = Input(Bool())
        val sret_commit = Input(Bool())
        val mtvec = Output(UInt(XLEN.W))
        val mepc_out = Output(UInt(XLEN.W))
        val trap_target = Output(UInt(XLEN.W))
        val xret_target = Output(UInt(XLEN.W))
        val current_privilege = Output(UInt(PRIV_MODE.width.W))
        val mret_illegal = Output(Bool())
        val sret_illegal = Output(Bool())
        val wfi_illegal = Output(Bool())
        val sfence_vma_illegal = Output(Bool())
        val mmu_context = Output(new BreezeMmuContext(XLEN))
        // WFI wakeup is intentionally distinct from interruptPending.  The
        // privileged architecture requires a locally enabled pending
        // interrupt to resume WFI regardless of mstatus.MIE/SIE or mideleg.
        val wfiWakeup = Output(Bool())
        val interruptPending = Output(Bool())
        val interruptCause = Output(UInt(XLEN.W))
        val csr_illegal = Output(Bool())
        val debug = if (enabledebug) Some(new CSRFDebugIO(XLEN)) else None
    })
    // csr supported
    val printer = RegInit(0.U(XLEN.W))
    val coreinst = RegInit(0.U(XLEN.W))
    val mcycle = RegInit(0.U(XLEN.W))
    val minstret = RegInit(0.U(XLEN.W))
    private val implementedHpmCounters = 8
    val mhpmcounter = RegInit(VecInit(Seq.fill(implementedHpmCounters)(0.U(XLEN.W))))
    val mhpmevent = RegInit(VecInit(Seq.fill(implementedHpmCounters)(0.U(XLEN.W))))
    val mcountinhibit = RegInit(0.U(32.W))
    val menvcfg = RegInit(0.U(XLEN.W))
    private val enableSupervisorUser = privilegeProfile.enableSupervisorUser
    val misa_value = (BigInt(2) << 62) | (BigInt(1) << 12) | (BigInt(1) << 8) |
        (BigInt(1) << 5) | (BigInt(1) << 3) | BigInt(1) |
        (if (enableCompressed) BigInt(1) << 2 else BigInt(0)) |
        (if (enableSupervisorUser) (BigInt(1) << 18) | (BigInt(1) << 20) else BigInt(0))
    val misa = WireDefault(misa_value.U(XLEN.W))
    val mvendorid = RegInit(0.U(32.W))
    val marchid = RegInit(0.U(XLEN.W))
    val mimpid = RegInit(0.U(XLEN.W))
    val mhartid = RegInit(hartId.U(XLEN.W)) // per-hart elaboration identity (Tile hartId)
    val mepc = RegInit(0.U(XLEN.W))
    val mtvec = RegInit(BigInt("200", 16).U(XLEN.W))
    val mcause = RegInit(0.U(XLEN.W))
    val mtval = RegInit(0.U(XLEN.W))
    val mscratch = RegInit(0.U(XLEN.W))
    val medeleg = RegInit(0.U(XLEN.W))
    val mideleg = RegInit(0.U(XLEN.W))
    val stvec = RegInit(0.U(XLEN.W))
    val sepc = RegInit(0.U(XLEN.W))
    val scause = RegInit(0.U(XLEN.W))
    val stval = RegInit(0.U(XLEN.W))
    val sscratch = RegInit(0.U(XLEN.W))
    val satp = RegInit(0.U(XLEN.W))
    val pmpcfg = RegInit(VecInit(Seq.fill(BreezePmpConfig.ActiveEntries)(0.U(8.W))))
    val pmpaddr = RegInit(VecInit(Seq.fill(BreezePmpConfig.ActiveEntries)(0.U(54.W))))
    // Keep all 16 CSR slots visible to firmware probing. Entries 8..15 have
    // no storage, ignore writes, and read as zero through both interfaces.
    val visiblePmpCfg = VecInit((0 until BreezePmpConfig.CsrEntries).map { index =>
        if (index < BreezePmpConfig.ActiveEntries) pmpcfg(index) else 0.U(8.W)
    })
    val visiblePmpAddr = VecInit((0 until BreezePmpConfig.CsrEntries).map { index =>
        if (index < BreezePmpConfig.ActiveEntries) pmpaddr(index) else 0.U(54.W)
    })
    val scounteren = RegInit(0.U(32.W))
    val stimecmp = RegInit(Fill(XLEN, 1.U(1.W)))
    val mcounteren = RegInit(0.U(32.W))
    val currentPrivilege = RegInit(PRIV_MODE.M.U(PRIV_MODE.width.W))
    // mstatus: distributed fields, assembled on read, disassembled on write
    val mstatus_MIE  = RegInit(false.B)      // bit 3:  machine interrupt enable
    val mstatus_MPIE = RegInit(false.B)      // bit 7:  machine previous interrupt enable
    val mstatus_MPP  = RegInit(PRIV_MODE.M.U(2.W))
    val mstatus_SIE  = RegInit(false.B)
    val mstatus_SPIE = RegInit(false.B)
    val mstatus_SPP  = RegInit(false.B)
    val mstatus_FS   = RegInit(0.U(2.W))     // bits 14-13: Off/Initial/Clean/Dirty
    val mstatus_TVM  = RegInit(false.B)      // bit 20: trap S-mode satp/SFENCE.VMA
    val mstatus_TW   = RegInit(false.B)      // bit 21: trap S-mode WFI
    val mstatus_TSR  = RegInit(false.B)      // bit 22: trap S-mode SRET
    val mstatus_MPRV = RegInit(false.B)
    val mstatus_SUM  = RegInit(false.B)
    val mstatus_MXR  = RegInit(false.B)
    val fflags = RegInit(0.U(5.W))
    val frm = RegInit(0.U(3.W))
    val mie_MSIE = RegInit(false.B)          // bit 3:  machine software interrupt enable
    val mie_MTIE = RegInit(false.B)          // bit 7:  machine timer interrupt enable
    val mie_MEIE = RegInit(false.B)          // bit 11: machine external interrupt enable
    val mie_SSIE = RegInit(false.B)
    val mie_STIE = RegInit(false.B)
    val mie_SEIE = RegInit(false.B)
    val sip_SSIP = RegInit(false.B)
    val mstatus_read = Wire(UInt(XLEN.W))
    val linuxXlenFields = if (enableSupervisorUser) {
        ((BigInt(2) << 34) | (BigInt(2) << 32)).U(XLEN.W)
    } else 0.U(XLEN.W)
    mstatus_read := ((mstatus_FS === "b11".U).asUInt << (XLEN - 1)) |
        linuxXlenFields | (mstatus_TSR.asUInt << 22) | (mstatus_TW.asUInt << 21) |
        (mstatus_TVM.asUInt << 20) | (mstatus_MXR.asUInt << 19) |
        (mstatus_SUM.asUInt << 18) | (mstatus_MPRV.asUInt << 17) |
        (mstatus_FS << 13) | (mstatus_MPP << 11) |
        (mstatus_SPP.asUInt << 8) | (mstatus_MPIE.asUInt << 7) |
        (mstatus_SPIE.asUInt << 5) | (mstatus_MIE.asUInt << 3) |
        (mstatus_SIE.asUInt << 1)
    val sstatus_read = Wire(UInt(XLEN.W))
    sstatus_read := ((mstatus_FS === "b11".U).asUInt << (XLEN - 1)) |
        (if (enableSupervisorUser) (BigInt(2) << 32).U(XLEN.W) else 0.U(XLEN.W)) |
        (mstatus_MXR.asUInt << 19) | (mstatus_SUM.asUInt << 18) |
        (mstatus_FS << 13) | (mstatus_SPP.asUInt << 8) |
        (mstatus_SPIE.asUInt << 5) | (mstatus_SIE.asUInt << 1)
    val mie_read = Wire(UInt(XLEN.W))
    val mip_read = Wire(UInt(XLEN.W))
    mie_read := (mie_MEIE.asUInt << MACHINE_INTERRUPT_CAUSE.EXTERNAL) |
        (mie_MTIE.asUInt << MACHINE_INTERRUPT_CAUSE.TIMER) |
        (mie_MSIE.asUInt << MACHINE_INTERRUPT_CAUSE.SOFTWARE) |
        (mie_SEIE.asUInt << SUPERVISOR_INTERRUPT_CAUSE.EXTERNAL) |
        (mie_STIE.asUInt << SUPERVISOR_INTERRUPT_CAUSE.TIMER) |
        (mie_SSIE.asUInt << SUPERVISOR_INTERRUPT_CAUSE.SOFTWARE)
    mip_read := (io.machineExternalInterrupt.asUInt << MACHINE_INTERRUPT_CAUSE.EXTERNAL) |
        (io.machineTimerInterrupt.asUInt << MACHINE_INTERRUPT_CAUSE.TIMER) |
        (io.machineSoftwareInterrupt.asUInt << MACHINE_INTERRUPT_CAUSE.SOFTWARE) |
        (io.supervisorExternalInterrupt.asUInt << SUPERVISOR_INTERRUPT_CAUSE.EXTERNAL) |
        ((menvcfg(63) && io.time >= stimecmp).asUInt << SUPERVISOR_INTERRUPT_CAUSE.TIMER) |
        (sip_SSIP.asUInt << SUPERVISOR_INTERRUPT_CAUSE.SOFTWARE)
    val sie_read = mie_read & mideleg
    val sip_read = mip_read & mideleg
    io.wfiWakeup := (mie_read & mip_read).orR
    def csrPattern(address: Int): BitPat = BitPat(address.U(12.W))
    val machineCsrFile = Seq(
        csrPattern(CSRMAP.fflags)  -> fflags,
        csrPattern(CSRMAP.frm)     -> frm,
        csrPattern(CSRMAP.fcsr)    -> Cat(frm, fflags),
        csrPattern(CSRMAP.printer) -> printer,
        csrPattern(CSRMAP.coreinst) -> coreinst,
        csrPattern(CSRMAP.misa)    -> misa,
        csrPattern(CSRMAP.mvendorid)-> mvendorid,
        csrPattern(CSRMAP.marchid)  -> marchid,
        csrPattern(CSRMAP.mimpid)    -> mimpid,
        csrPattern(CSRMAP.mhartid)  -> mhartid,
        csrPattern(CSRMAP.mepc)     -> mepc,
        csrPattern(CSRMAP.mtvec)    -> mtvec,
        csrPattern(CSRMAP.mcause)   -> mcause,
        csrPattern(CSRMAP.mtval)    -> mtval,
        csrPattern(CSRMAP.mscratch) -> mscratch,
        csrPattern(CSRMAP.mstatus)  -> mstatus_read,
        csrPattern(CSRMAP.medeleg)  -> medeleg,
        csrPattern(CSRMAP.mideleg)  -> mideleg,
        csrPattern(CSRMAP.mie)      -> mie_read,
        csrPattern(CSRMAP.mip)      -> mip_read,
        csrPattern(CSRMAP.mcounteren) -> mcounteren,
        csrPattern(CSRMAP.mcycle)   -> mcycle,
        csrPattern(CSRMAP.minstret) -> minstret,
        csrPattern(CSRMAP.cycle)    -> mcycle,
        csrPattern(CSRMAP.time)     -> io.time,
        csrPattern(CSRMAP.instret)  -> minstret,
        csrPattern(CSRMAP.mcountinhibit) -> mcountinhibit,
        csrPattern(CSRMAP.menvcfg) -> menvcfg,
        csrPattern(CSRMAP.pmpcfg0) -> Cat(visiblePmpCfg.slice(0, 8).reverse),
        csrPattern(CSRMAP.pmpcfg2) -> Cat(visiblePmpCfg.slice(8, 16).reverse)
    ) ++ (0 until BreezePmpConfig.CsrEntries).map(index =>
        csrPattern(CSRMAP.pmpaddr0 + index) -> visiblePmpAddr(index))
    val supervisorCsrFile = if (enableSupervisorUser) Seq(
        csrPattern(CSRMAP.sstatus) -> sstatus_read,
        csrPattern(CSRMAP.sie) -> sie_read,
        csrPattern(CSRMAP.stvec) -> stvec,
        csrPattern(CSRMAP.scounteren) -> scounteren,
        csrPattern(CSRMAP.sscratch) -> sscratch,
        csrPattern(CSRMAP.sepc) -> sepc,
        csrPattern(CSRMAP.scause) -> scause,
        csrPattern(CSRMAP.stval) -> stval,
        csrPattern(CSRMAP.sip) -> sip_read,
        csrPattern(CSRMAP.stimecmp) -> stimecmp,
        csrPattern(CSRMAP.satp) -> satp
    ) else Seq.empty
    val csrFile = machineCsrFile ++ supervisorCsrFile ++
      (0 until implementedHpmCounters).flatMap { index => Seq(
        csrPattern(CSRMAP.mhpmcounter3 + index) -> mhpmcounter(index),
        csrPattern(CSRMAP.hpmcounter3 + index) -> mhpmcounter(index),
        csrPattern(CSRMAP.mhpmevent3 + index) -> mhpmevent(index)
    ) }
    val old_csr_val = WireDefault(0.U(XLEN.W))
    val new_csr_val = WireDefault(old_csr_val)
    val read_csr = Wire(Bool())
    val write_csr = Wire(Bool())
    val uimm = io.csr_reg_data(4,0)
    read_csr := false.B
    write_csr := false.B
    // Zicsr对寄存器的读
    when(read_csr){
        old_csr_val := Lookup(io.csr_addr, 0.U, csrFile)
    }.otherwise{
        old_csr_val := 0.U
    }
    val fpCsrAccess = io.csr_addr === CSRMAP.fflags.U ||
        io.csr_addr === CSRMAP.frm.U || io.csr_addr === CSRMAP.fcsr.U
    val machineAddresses = Seq(
        CSRMAP.fflags, CSRMAP.frm, CSRMAP.fcsr, CSRMAP.printer, CSRMAP.coreinst,
        CSRMAP.misa, CSRMAP.mvendorid, CSRMAP.marchid, CSRMAP.mimpid, CSRMAP.mhartid,
        CSRMAP.mstatus, CSRMAP.mie, CSRMAP.mtvec,
        CSRMAP.mcounteren, CSRMAP.mscratch, CSRMAP.mepc, CSRMAP.mcause, CSRMAP.mtval,
        CSRMAP.mip, CSRMAP.mcycle, CSRMAP.minstret, CSRMAP.cycle, CSRMAP.time, CSRMAP.instret,
        CSRMAP.mcountinhibit, CSRMAP.menvcfg, CSRMAP.pmpcfg0, CSRMAP.pmpcfg2) ++
        (0 until BreezePmpConfig.CsrEntries).map(CSRMAP.pmpaddr0 + _) ++
        (0 until implementedHpmCounters).flatMap(index => Seq(
            CSRMAP.mhpmcounter3 + index, CSRMAP.hpmcounter3 + index,
            CSRMAP.mhpmevent3 + index))
    val supervisorAddresses = Seq(
        CSRMAP.sstatus, CSRMAP.sie, CSRMAP.stvec, CSRMAP.scounteren,
        CSRMAP.sscratch, CSRMAP.sepc, CSRMAP.scause, CSRMAP.stval,
        CSRMAP.sip, CSRMAP.stimecmp, CSRMAP.satp)
    val supervisorMachineAddresses = Seq(CSRMAP.medeleg, CSRMAP.mideleg)
    val implementedAddresses = machineAddresses ++
        (if (enableSupervisorUser)
            supervisorMachineAddresses ++ supervisorAddresses
        else Seq.empty)
    val csrImplemented = implementedAddresses.map(address =>
        io.csr_addr === address.U(12.W)).reduce(_ || _)
    val csrAccess = io.csr_cmd =/= CSR_CMD.NOP.U
    val privilegeDenied = currentPrivilege < io.csr_addr(9, 8)
    // These simulation-only CSRs live in a non-standard U-privilege address
    // range, but remain machine-only in the Linux profile.
    val customCsrDenied = currentPrivilege =/= PRIV_MODE.M.U &&
        (io.csr_addr === CSRMAP.printer.U || io.csr_addr === CSRMAP.coreinst.U)
    val satpDenied = currentPrivilege === PRIV_MODE.S.U && mstatus_TVM &&
        io.csr_addr === CSRMAP.satp.U
    val sstcDenied = io.csr_addr === CSRMAP.stimecmp.U && !menvcfg(63)
    val readOnlyWrite = io.csr_addr(11, 10) === 3.U && write_csr
    val counterIndex = MuxLookup(io.csr_addr, 0.U(5.W))(Seq(
        CSRMAP.cycle.U -> 0.U,
        CSRMAP.time.U -> 1.U,
        CSRMAP.instret.U -> 2.U
    ) ++ (0 until implementedHpmCounters).map(index =>
        (CSRMAP.hpmcounter3 + index).U -> (index + 3).U))
    val userCounterAccess = io.csr_addr === CSRMAP.cycle.U || io.csr_addr === CSRMAP.time.U ||
        io.csr_addr === CSRMAP.instret.U ||
        (io.csr_addr >= CSRMAP.hpmcounter3.U &&
          io.csr_addr < (CSRMAP.hpmcounter3 + implementedHpmCounters).U)
    val counterDenied = userCounterAccess && Mux(
        currentPrivilege === PRIV_MODE.S.U,
        !mcounteren(counterIndex),
        Mux(currentPrivilege === PRIV_MODE.U.U,
            !mcounteren(counterIndex) || !scounteren(counterIndex), false.B))
    // CSR support is whitelist-based in every privilege profile. Unknown CSR
    // addresses must raise an illegal-instruction exception; returning zero
    // would make probing firmware falsely advertise extensions that are not
    // implemented by the core.
    io.csr_illegal := csrAccess && (
        !csrImplemented || privilegeDenied || customCsrDenied || satpDenied || sstcDenied ||
        readOnlyWrite || counterDenied ||
        (fpCsrAccess && mstatus_FS === 0.U))

    when(!io.trap.valid){
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
    }
    // FP arithmetic flags are sticky. A killed instruction never asserts
    // fp_commit_valid, so speculative FPnew responses cannot alter CSR state.
    when(io.fp_commit_valid) {
        fflags := fflags | io.fp_flags
        mstatus_FS := "b11".U
    }

    // Zicsr对寄存器的写在wb阶段提交
    val medelegMask = Seq(1, 2, 4, 5, 6, 7, 8, 9, 12, 13, 15)
        .map(bit => BigInt(1) << bit).reduce(_ | _).U(XLEN.W)
    val midelegMask = Seq(
        SUPERVISOR_INTERRUPT_CAUSE.SOFTWARE,
        SUPERVISOR_INTERRUPT_CAUSE.TIMER,
        SUPERVISOR_INTERRUPT_CAUSE.EXTERNAL)
        .map(bit => BigInt(1) << bit).reduce(_ | _).U(XLEN.W)
    when(io.commit_valid && io.commit_write_en && !io.trap.valid){
        switch(io.commit_addr){
            is(CSRMAP.fflags.U) {
                fflags := io.commit_wdata(4, 0)
                mstatus_FS := "b11".U
            }
            is(CSRMAP.frm.U) {
                frm := Mux(io.commit_wdata(2, 0) <= 4.U, io.commit_wdata(2, 0), 0.U)
                mstatus_FS := "b11".U
            }
            is(CSRMAP.fcsr.U) {
                fflags := io.commit_wdata(4, 0)
                frm := Mux(io.commit_wdata(7, 5) <= 4.U, io.commit_wdata(7, 5), 0.U)
                mstatus_FS := "b11".U
            }
            is(CSRMAP.printer.U){
                printer := io.commit_wdata
                if(dumplog){
                    printf(cf"[INFO] printer = 0x${io.commit_wdata}%x\n")
                }
            }
            is(CSRMAP.coreinst.U){
                if(dumplog){
                    printf(cf"[INFO] coreinst = 0x${coreinst}%x\n")
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
                    printf(cf"[INFO] mhartid = ${mhartid}%x\n")
                }
            }
            is(CSRMAP.mtvec.U){
                // Direct (0) and Vectored (1) are supported. Reserved MODE
                // values are WARL-coerced to Direct.
                val requestedMode = io.commit_wdata(1, 0)
                val legalMode = Mux(requestedMode === 1.U, 1.U(2.W), 0.U(2.W))
                mtvec := Cat(io.commit_wdata(XLEN - 1, 2), legalMode)
                if(dumplog){
                    printf(cf"[INFO] mtvec = 0x${io.commit_wdata}%x\n")
                }
            }
            is(CSRMAP.mepc.U){
                mepc := (if (enableCompressed) Cat(io.commit_wdata(XLEN - 1, 1), 0.U(1.W))
                    else Cat(io.commit_wdata(XLEN - 1, 2), 0.U(2.W)))
                if(dumplog){
                    printf(cf"[INFO] mepc = 0x${io.commit_wdata}%x\n")
                }
            }
            is(CSRMAP.mcause.U){
                mcause := io.commit_wdata
                if(dumplog){
                    printf(cf"[INFO] mcause = 0x${io.commit_wdata}%x\n")
                }
            }
            is(CSRMAP.mtval.U){
                mtval := io.commit_wdata
                if(dumplog){
                    printf(cf"[INFO] mtval = 0x${io.commit_wdata}%x\n")
                }
            }
            is(CSRMAP.mscratch.U){
                mscratch := io.commit_wdata
                if(dumplog){
                    printf(cf"[INFO] mscratch = 0x${io.commit_wdata}%x\n")
                }
            }
            is(CSRMAP.mstatus.U){
                mstatus_MIE  := io.commit_wdata(3)
                mstatus_MPIE := io.commit_wdata(7)
                if (enableSupervisorUser) {
                    mstatus_SIE := io.commit_wdata(1)
                    mstatus_SPIE := io.commit_wdata(5)
                    mstatus_SPP := io.commit_wdata(8)
                    val requestedMpp = io.commit_wdata(12, 11)
                    mstatus_MPP := Mux(requestedMpp === 2.U,
                        PRIV_MODE.U.U, requestedMpp)
                    mstatus_TVM := io.commit_wdata(20)
                    mstatus_TW := io.commit_wdata(21)
                    mstatus_TSR := io.commit_wdata(22)
                    mstatus_MPRV := io.commit_wdata(17)
                    mstatus_SUM := io.commit_wdata(18)
                    mstatus_MXR := io.commit_wdata(19)
                } else {
                    mstatus_MPP := io.commit_wdata(12, 11)
                }
                mstatus_FS   := io.commit_wdata(14, 13)
                if(dumplog){
                    printf(cf"[INFO] mstatus write: MIE=${io.commit_wdata(3)} MPIE=${io.commit_wdata(7)} MPP=${io.commit_wdata(12,11)}\n")
                }
            }
            is(CSRMAP.medeleg.U) {
                if (enableSupervisorUser) medeleg := io.commit_wdata & medelegMask
            }
            is(CSRMAP.mideleg.U) {
                if (enableSupervisorUser) mideleg := io.commit_wdata & midelegMask
            }
            is(CSRMAP.mie.U){
                mie_MSIE := io.commit_wdata(MACHINE_INTERRUPT_CAUSE.SOFTWARE)
                mie_MTIE := io.commit_wdata(MACHINE_INTERRUPT_CAUSE.TIMER)
                mie_MEIE := io.commit_wdata(MACHINE_INTERRUPT_CAUSE.EXTERNAL)
                if (enableSupervisorUser) {
                    mie_SSIE := io.commit_wdata(SUPERVISOR_INTERRUPT_CAUSE.SOFTWARE)
                    mie_STIE := io.commit_wdata(SUPERVISOR_INTERRUPT_CAUSE.TIMER)
                    mie_SEIE := io.commit_wdata(SUPERVISOR_INTERRUPT_CAUSE.EXTERNAL)
                }
            }
            is(CSRMAP.mip.U){
                // MSIP, MTIP and MEIP are read-only reflections of platform
                // inputs (software clears MSIP through the CLINT msip word).
                if (enableSupervisorUser) {
                    sip_SSIP := io.commit_wdata(SUPERVISOR_INTERRUPT_CAUSE.SOFTWARE)
                }
            }
            is(CSRMAP.mcounteren.U) {
                if (enableSupervisorUser) mcounteren := io.commit_wdata(31, 0)
            }
            is(CSRMAP.sstatus.U) {
                if (enableSupervisorUser) {
                    mstatus_SIE := io.commit_wdata(1)
                    mstatus_SPIE := io.commit_wdata(5)
                    mstatus_SPP := io.commit_wdata(8)
                    mstatus_SUM := io.commit_wdata(18)
                    mstatus_MXR := io.commit_wdata(19)
                    mstatus_FS := io.commit_wdata(14, 13)
                }
            }
            is(CSRMAP.sie.U) {
                if (enableSupervisorUser) {
                    mie_SSIE := io.commit_wdata(SUPERVISOR_INTERRUPT_CAUSE.SOFTWARE) &&
                        mideleg(SUPERVISOR_INTERRUPT_CAUSE.SOFTWARE)
                    mie_STIE := io.commit_wdata(SUPERVISOR_INTERRUPT_CAUSE.TIMER) &&
                        mideleg(SUPERVISOR_INTERRUPT_CAUSE.TIMER)
                    mie_SEIE := io.commit_wdata(SUPERVISOR_INTERRUPT_CAUSE.EXTERNAL) &&
                        mideleg(SUPERVISOR_INTERRUPT_CAUSE.EXTERNAL)
                }
            }
            is(CSRMAP.stvec.U) {
                if (enableSupervisorUser) {
                    val legalMode = Mux(io.commit_wdata(1, 0) === 1.U, 1.U(2.W), 0.U(2.W))
                    stvec := Cat(io.commit_wdata(XLEN - 1, 2), legalMode)
                }
            }
            is(CSRMAP.scounteren.U) {
                if (enableSupervisorUser) scounteren := io.commit_wdata(31, 0)
            }
            is(CSRMAP.sscratch.U) {
                if (enableSupervisorUser) sscratch := io.commit_wdata
            }
            is(CSRMAP.sepc.U) {
                if (enableSupervisorUser) {
                    sepc := (if (enableCompressed) Cat(io.commit_wdata(XLEN - 1, 1), 0.U(1.W))
                        else Cat(io.commit_wdata(XLEN - 1, 2), 0.U(2.W)))
                }
            }
            is(CSRMAP.scause.U) {
                if (enableSupervisorUser) scause := io.commit_wdata
            }
            is(CSRMAP.stval.U) {
                if (enableSupervisorUser) stval := io.commit_wdata
            }
            is(CSRMAP.sip.U) {
                if (enableSupervisorUser) {
                    sip_SSIP := io.commit_wdata(SUPERVISOR_INTERRUPT_CAUSE.SOFTWARE) &&
                        mideleg(SUPERVISOR_INTERRUPT_CAUSE.SOFTWARE)
                }
            }
            is(CSRMAP.satp.U) {
                if (enableSupervisorUser) {
                    // RV64 satp implements Bare (0) and Sv39 (8), ASID[15:0]
                    // and the 44-bit root PPN. Unsupported MODE writes become Bare.
                    satp := Mux(io.commit_wdata(63, 60) === 8.U,
                        Cat(8.U(4.W), io.commit_wdata(59, 0)), 0.U)
                }
            }
            is(CSRMAP.mcycle.U, CSRMAP.minstret.U){
                // Updated below so an explicit CSR write has priority over
                // the automatic per-cycle/per-retirement increments.
            }
            is(CSRMAP.mcountinhibit.U) {
                val supportedMask = ((BigInt(1) << 0) | (BigInt(1) << 2) |
                    ((BigInt(1) << implementedHpmCounters) - 1) << 3).U(32.W)
                mcountinhibit := io.commit_wdata(31, 0) & supportedMask
            }
            is(CSRMAP.menvcfg.U) {
                if (enableSupervisorUser) {
                    // Sstc STCE and Svade ADUE are the implemented fields.
                    menvcfg := io.commit_wdata & ((BigInt(1) << 63) | (BigInt(1) << 61)).U
                }
            }
            is(CSRMAP.stimecmp.U) {
                if (enableSupervisorUser) {
                    when(menvcfg(63)) { stimecmp := io.commit_wdata }
                }
            }
            is(CSRMAP.pmpcfg0.U, CSRMAP.pmpcfg2.U) {
                val base = Mux(io.commit_addr === CSRMAP.pmpcfg0.U, 0.U, 8.U)
                for (index <- 0 until BreezePmpConfig.ActiveEntries) {
                    when(base === (index & 8).U) {
                        val lane = index & 7
                        val requested = io.commit_wdata(8 * lane + 7, 8 * lane)
                        val legal = Cat(requested(7), 0.U(2.W), requested(4, 3),
                            requested(2), Mux(requested(0), requested(1), false.B), requested(0))
                        when(!pmpcfg(index)(7)) { pmpcfg(index) := legal }
                    }
                }
            }
        }
        for (index <- 0 until BreezePmpConfig.ActiveEntries) {
            val ownLocked = pmpcfg(index)(7)
            val nextTorLocked = if (index == BreezePmpConfig.ActiveEntries - 1) false.B else
                pmpcfg(index + 1)(7) && pmpcfg(index + 1)(4, 3) === 1.U
            when(io.commit_addr === (CSRMAP.pmpaddr0 + index).U &&
                !ownLocked && !nextTorLocked) {
                pmpaddr(index) := io.commit_wdata(53, 0)
            }
        }
    }
    for (index <- 0 until implementedHpmCounters) {
        val writeSelector = io.commit_valid && io.commit_write_en &&
            !io.trap.valid && io.commit_addr === (CSRMAP.mhpmevent3 + index).U
        when(writeSelector) {
            mhpmevent(index) := Mux(
                io.commit_wdata <= BREEZE_HPM_EVENT.LOAD_USE_STALL.U,
                io.commit_wdata,
                0.U)
        }
    }
    // 更新寄存器的值
    when(io.retire_valid){
        coreinst := coreinst + 1.U
    }
    val writeMcycle = io.commit_valid && io.commit_write_en &&
        !io.trap.valid && io.commit_addr === CSRMAP.mcycle.U
    val writeMinstret = io.commit_valid && io.commit_write_en &&
        !io.trap.valid && io.commit_addr === CSRMAP.minstret.U
    when(writeMcycle) {
        mcycle := io.commit_wdata
    }.elsewhen(!mcountinhibit(0)) {
        mcycle := mcycle + 1.U
    }
    when(writeMinstret) {
        minstret := io.commit_wdata
    }.elsewhen(io.retire_valid && !mcountinhibit(2)) {
        minstret := minstret + 1.U
    }
    def selectedHpmEvent(selector: UInt): Bool = MuxLookup(selector, false.B)(Seq(
        BREEZE_HPM_EVENT.CONTROL_RETIRED.U -> io.hpmEvents.controlRetired,
        BREEZE_HPM_EVENT.CONTROL_TAKEN.U -> io.hpmEvents.controlTaken,
        BREEZE_HPM_EVENT.PREDICTION_MISS.U -> io.hpmEvents.predictionMiss,
        BREEZE_HPM_EVENT.ICACHE_ACCESS.U -> io.hpmEvents.icacheAccess,
        BREEZE_HPM_EVENT.ICACHE_MISS.U -> io.hpmEvents.icacheMiss,
        BREEZE_HPM_EVENT.DCACHE_ACCESS.U -> io.hpmEvents.dcacheAccess,
        BREEZE_HPM_EVENT.DCACHE_MISS.U -> io.hpmEvents.dcacheMiss,
        BREEZE_HPM_EVENT.DCACHE_UNCACHED.U -> io.hpmEvents.dcacheUncached,
        BREEZE_HPM_EVENT.MEM_STALL_CYCLE.U -> io.hpmEvents.memStallCycle,
        BREEZE_HPM_EVENT.LOAD_USE_STALL.U -> io.hpmEvents.loadUseStall
    ))
    for (index <- 0 until implementedHpmCounters) {
        val writeCounter = io.commit_valid && io.commit_write_en &&
            !io.trap.valid && io.commit_addr === (CSRMAP.mhpmcounter3 + index).U
        when(writeCounter) {
            mhpmcounter(index) := io.commit_wdata
        }.elsewhen(!mcountinhibit(index + 3) && selectedHpmEvent(mhpmevent(index))) {
            mhpmcounter(index) := mhpmcounter(index) + 1.U
        }
    }
    val trapDelegated = if (enableSupervisorUser) {
        currentPrivilege =/= PRIV_MODE.M.U && Mux(
            io.trap.is_interrupt,
            mideleg(io.trap.cause(5, 0)),
            medeleg(io.trap.cause(5, 0)))
    } else false.B
    val mtvecBase = Cat(mtvec(XLEN - 1, 2), 0.U(2.W))
    val stvecBase = Cat(stvec(XLEN - 1, 2), 0.U(2.W))
    val machineTrapTarget = Mux(io.trap.is_interrupt && mtvec(1, 0) === 1.U,
        mtvecBase + (io.trap.cause << 2), mtvecBase)
    val supervisorTrapTarget = Mux(io.trap.is_interrupt && stvec(1, 0) === 1.U,
        stvecBase + (io.trap.cause << 2), stvecBase)

    // Trap entry is precise at WB. A trap raised in M is never delegated.
    when(io.trap.valid){
        when(trapDelegated) {
            sepc := (if (enableCompressed) Cat(io.trap.pc(XLEN - 1, 1), 0.U(1.W))
                else Cat(io.trap.pc(XLEN - 1, 2), 0.U(2.W)))
            scause := io.trap.cause | (io.trap.is_interrupt.asUInt << (XLEN - 1))
            stval := Mux(io.trap.is_interrupt, 0.U, io.trap.tval)
            mstatus_SPIE := mstatus_SIE
            mstatus_SIE := false.B
            mstatus_SPP := currentPrivilege === PRIV_MODE.S.U
            currentPrivilege := PRIV_MODE.S.U
        }.otherwise {
            mepc := (if (enableCompressed) Cat(io.trap.pc(XLEN - 1, 1), 0.U(1.W))
                else Cat(io.trap.pc(XLEN - 1, 2), 0.U(2.W)))
            mcause := io.trap.cause | (io.trap.is_interrupt.asUInt << (XLEN - 1))
            mtval := Mux(io.trap.is_interrupt, 0.U, io.trap.tval)
            mstatus_MPIE := mstatus_MIE
            mstatus_MIE  := false.B
            mstatus_MPP  := Mux(enableSupervisorUser.B,
                currentPrivilege, PRIV_MODE.M.U)
            currentPrivilege := PRIV_MODE.M.U
        }
    }
    // MRET: restore mstatus from saved state
    when(io.mret_commit){
        mstatus_MIE  := mstatus_MPIE
        mstatus_MPIE := true.B
        if (enableSupervisorUser) {
            currentPrivilege := mstatus_MPP
            when(mstatus_MPP =/= PRIV_MODE.M.U) { mstatus_MPRV := false.B }
            mstatus_MPP := PRIV_MODE.U.U
        } else {
            currentPrivilege := PRIV_MODE.M.U
            mstatus_MPP := PRIV_MODE.M.U
        }
    }
    when(io.sret_commit && enableSupervisorUser.B) {
        mstatus_SIE := mstatus_SPIE
        mstatus_SPIE := true.B
        currentPrivilege := Mux(mstatus_SPP, PRIV_MODE.S.U, PRIV_MODE.U.U)
        mstatus_SPP := false.B
    }
    io.csr_old_data := old_csr_val
    io.csr_new_data := new_csr_val
    io.csr_write_en := write_csr
    io.mtvec := mtvec
    io.mepc_out := mepc
    io.trap_target := Mux(trapDelegated, supervisorTrapTarget, machineTrapTarget)
    io.xret_target := Mux(io.sret_commit, sepc, mepc)
    io.current_privilege := currentPrivilege
    io.mret_illegal := enableSupervisorUser.B && currentPrivilege =/= PRIV_MODE.M.U
    io.sret_illegal := !enableSupervisorUser.B ||
        currentPrivilege === PRIV_MODE.U.U ||
        (currentPrivilege === PRIV_MODE.S.U && mstatus_TSR)
    io.wfi_illegal := !enableSupervisorUser.B ||
        currentPrivilege === PRIV_MODE.U.U ||
        (currentPrivilege === PRIV_MODE.S.U && mstatus_TW)
    io.sfence_vma_illegal := !enableSupervisorUser.B ||
        currentPrivilege === PRIV_MODE.U.U ||
        (currentPrivilege === PRIV_MODE.S.U && mstatus_TVM)
    io.mmu_context.satp := satp
    io.mmu_context.privilege := currentPrivilege
    io.mmu_context.mprv := mstatus_MPRV
    io.mmu_context.mpp := mstatus_MPP
    io.mmu_context.sum := mstatus_SUM
    io.mmu_context.mxr := mstatus_MXR
    io.mmu_context.adue := menvcfg(61)
    io.mmu_context.pmpcfg := visiblePmpCfg
    io.mmu_context.pmpaddr := visiblePmpAddr
    io.frm := frm
    io.fp_enabled := mstatus_FS =/= 0.U
    // Fixed priority: MEI > MSI > MTI > SEI > SSI > STI. xIE gates an
    // interrupt only while executing at that same privilege level.
    val machineGlobalEnable = currentPrivilege =/= PRIV_MODE.M.U || mstatus_MIE
    val supervisorGlobalEnable = enableSupervisorUser.B &&
        (currentPrivilege === PRIV_MODE.U.U ||
          (currentPrivilege === PRIV_MODE.S.U && mstatus_SIE))
    val externalInterruptPending = machineGlobalEnable && mie_MEIE && io.machineExternalInterrupt
    val softwareInterruptPending = machineGlobalEnable && mie_MSIE && io.machineSoftwareInterrupt
    val timerInterruptPending = machineGlobalEnable && mie_MTIE && io.machineTimerInterrupt
    val supervisorExternalPending = supervisorGlobalEnable && mie_SEIE &&
        mideleg(SUPERVISOR_INTERRUPT_CAUSE.EXTERNAL) &&
        mip_read(SUPERVISOR_INTERRUPT_CAUSE.EXTERNAL)
    val supervisorSoftwarePending = supervisorGlobalEnable && mie_SSIE &&
        mideleg(SUPERVISOR_INTERRUPT_CAUSE.SOFTWARE) && sip_SSIP
    val supervisorTimerPending = supervisorGlobalEnable && mie_STIE &&
        mideleg(SUPERVISOR_INTERRUPT_CAUSE.TIMER) &&
        mip_read(SUPERVISOR_INTERRUPT_CAUSE.TIMER)
    io.interruptPending := externalInterruptPending || softwareInterruptPending ||
        timerInterruptPending || supervisorExternalPending ||
        supervisorSoftwarePending || supervisorTimerPending
    io.interruptCause := MuxCase(MACHINE_INTERRUPT_CAUSE.TIMER.U(XLEN.W), Seq(
        externalInterruptPending -> MACHINE_INTERRUPT_CAUSE.EXTERNAL.U(XLEN.W),
        softwareInterruptPending -> MACHINE_INTERRUPT_CAUSE.SOFTWARE.U(XLEN.W),
        timerInterruptPending -> MACHINE_INTERRUPT_CAUSE.TIMER.U(XLEN.W),
        supervisorExternalPending -> SUPERVISOR_INTERRUPT_CAUSE.EXTERNAL.U(XLEN.W),
        supervisorSoftwarePending -> SUPERVISOR_INTERRUPT_CAUSE.SOFTWARE.U(XLEN.W),
        supervisorTimerPending -> SUPERVISOR_INTERRUPT_CAUSE.TIMER.U(XLEN.W)
    ))
    io.debug.foreach { debug =>
        debug.mcause := mcause
        debug.mepc  := mepc
    }
}
