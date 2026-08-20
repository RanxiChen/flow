package flow.fpu

import chisel3._
import chisel3.util._
import java.nio.file.{Files, Path, Paths}

/** Encodings intentionally match fpnew_pkg.sv.  Keeping the project-owned
  * request flat prevents fpnew package types from leaking into the Chisel
  * pipeline.
  */
object BreezeFpOp {
    val FMADD = 0
    val FNMSUB = 1
    val ADD = 2
    val MUL = 3
    val DIV = 4
    val SQRT = 5
    val SGNJ = 6
    val MINMAX = 7
    val CMP = 8
    val CLASSIFY = 9
    val F2F = 10
    val F2I = 11
    val I2F = 12
    val width = 4
}

object BreezeFpFmt {
    val S = 0 // fpnew_pkg::FP32
    val D = 1 // fpnew_pkg::FP64
    val width = 3
}

object BreezeIntFmt {
    val W = 2 // fpnew_pkg::INT32
    val L = 3 // fpnew_pkg::INT64
    val width = 2
}

object BreezeFpLocalOp {
    val NONE = 0
    val FMV_X = 1
    val FMV_F = 2
    val width = 2
}

class BreezeFpCtrl extends Bundle {
    val valid = Bool()
    val fpuValid = Bool()
    val localOp = UInt(BreezeFpLocalOp.width.W)
    val isLoad = Bool()
    val isStore = Bool()
    val isDouble = Bool()
    val writesFpr = Bool()
    val writesGpr = Bool()
    val usesFpr1 = Bool()
    val usesFpr2 = Bool()
    val usesFpr3 = Bool()
    val usesGpr1 = Bool()
    val operation = UInt(BreezeFpOp.width.W)
    val opMod = Bool()
    val srcFmt = UInt(BreezeFpFmt.width.W)
    val dstFmt = UInt(BreezeFpFmt.width.W)
    val intFmt = UInt(BreezeIntFmt.width.W)
    val rm = UInt(3.W)
    val usesArchitecturalRm = Bool()
    val writeFlags = Bool()
}

/** Complete scalar RV64F/RV64D decoder.  The ordinary integer decoder remains
  * untouched; BreezeBackend accepts an instruction when either decoder does.
  */
class BreezeFpDecoder extends Module {
    val io = IO(new Bundle {
        val inst = Input(UInt(32.W))
        val ctrl = Output(new BreezeFpCtrl)
        val illegal = Output(Bool())
    })

    val opcode = io.inst(6, 0)
    val funct3 = io.inst(14, 12)
    val funct5 = io.inst(31, 27)
    val fmt = io.inst(26, 25)
    val rs2 = io.inst(24, 20)
    val legalFmt = fmt === 0.U || fmt === 1.U
    val legalRm = funct3 <= 4.U || funct3 === 7.U

    io.ctrl := 0.U.asTypeOf(new BreezeFpCtrl)
    io.ctrl.srcFmt := fmt
    io.ctrl.dstFmt := fmt
    io.ctrl.intFmt := BreezeIntFmt.W.U
    io.ctrl.rm := funct3
    io.illegal := true.B

    def accept(): Unit = {
        io.ctrl.valid := true.B
        io.illegal := false.B
    }
    def arithmetic(op: Int, operands: Int = 2): Unit = {
        accept()
        io.ctrl.fpuValid := true.B
        io.ctrl.writesFpr := true.B
        io.ctrl.usesFpr1 := true.B
        io.ctrl.usesFpr2 := (operands >= 2).B
        io.ctrl.usesFpr3 := (operands >= 3).B
        io.ctrl.operation := op.U
        io.ctrl.usesArchitecturalRm := true.B
        io.ctrl.writeFlags := true.B
    }

    switch(opcode) {
        is("b0000111".U) { // LOAD-FP
            when(funct3 === "b010".U || funct3 === "b011".U) {
                accept()
                io.ctrl.isLoad := true.B
                io.ctrl.isDouble := funct3 === "b011".U
                io.ctrl.writesFpr := true.B
                io.ctrl.usesGpr1 := true.B
            }
        }
        is("b0100111".U) { // STORE-FP
            when(funct3 === "b010".U || funct3 === "b011".U) {
                accept()
                io.ctrl.isStore := true.B
                io.ctrl.isDouble := funct3 === "b011".U
                io.ctrl.usesGpr1 := true.B
                io.ctrl.usesFpr2 := true.B
            }
        }
        is("b1000011".U, "b1000111".U, "b1001011".U, "b1001111".U) {
            when(legalFmt && legalRm) {
                arithmetic(BreezeFpOp.FMADD, 3)
                // FMADD/FMSUB use FMADD; FNMSUB/FNMADD use FNMSUB.
                io.ctrl.operation := Mux(opcode === "b1001011".U || opcode === "b1001111".U,
                    BreezeFpOp.FNMSUB.U, BreezeFpOp.FMADD.U)
                io.ctrl.opMod := opcode === "b1000111".U || opcode === "b1001111".U
            }
        }
        is("b1010011".U) { // OP-FP
            when(legalFmt) {
                switch(funct5) {
                    is("b00000".U) { when(legalRm) { arithmetic(BreezeFpOp.ADD); io.ctrl.opMod := false.B } }
                    is("b00001".U) { when(legalRm) { arithmetic(BreezeFpOp.ADD); io.ctrl.opMod := true.B } }
                    is("b00010".U) { when(legalRm) { arithmetic(BreezeFpOp.MUL) } }
                    is("b00011".U) { when(legalRm) { arithmetic(BreezeFpOp.DIV) } }
                    is("b01011".U) {
                        when(rs2 === 0.U && legalRm) { arithmetic(BreezeFpOp.SQRT, 1) }
                    }
                    is("b00100".U) { // FSGNJ/FSGNJN/FSGNJX
                        when(funct3 <= 2.U) {
                            arithmetic(BreezeFpOp.SGNJ)
                            io.ctrl.usesArchitecturalRm := false.B
                            io.ctrl.rm := funct3
                            io.ctrl.writeFlags := false.B
                        }
                    }
                    is("b00101".U) { // FMIN/FMAX
                        when(funct3 <= 1.U) {
                            arithmetic(BreezeFpOp.MINMAX)
                            io.ctrl.usesArchitecturalRm := false.B
                            io.ctrl.rm := funct3
                        }
                    }
                    is("b01000".U) { // FCVT.S.D / FCVT.D.S
                        when((rs2 === 0.U || rs2 === 1.U) && rs2 =/= fmt && legalRm) {
                            arithmetic(BreezeFpOp.F2F, 1)
                            io.ctrl.srcFmt := rs2
                            io.ctrl.dstFmt := fmt
                        }
                    }
                    is("b10100".U) { // FLE/FLT/FEQ
                        when(funct3 <= 2.U) {
                            arithmetic(BreezeFpOp.CMP)
                            io.ctrl.writesFpr := false.B
                            io.ctrl.writesGpr := true.B
                            io.ctrl.usesArchitecturalRm := false.B
                            io.ctrl.rm := funct3
                        }
                    }
                    is("b11000".U) { // FCVT.{W,WU,L,LU}.{S,D}
                        when(rs2 <= 3.U && legalRm) {
                            arithmetic(BreezeFpOp.F2I, 1)
                            io.ctrl.writesFpr := false.B
                            io.ctrl.writesGpr := true.B
                            io.ctrl.intFmt := Mux(rs2(1), BreezeIntFmt.L.U, BreezeIntFmt.W.U)
                            io.ctrl.opMod := rs2(0)
                        }
                    }
                    is("b11010".U) { // FCVT.{S,D}.{W,WU,L,LU}
                        when(rs2 <= 3.U && legalRm) {
                            arithmetic(BreezeFpOp.I2F, 0)
                            io.ctrl.usesFpr1 := false.B
                            io.ctrl.usesGpr1 := true.B
                            io.ctrl.intFmt := Mux(rs2(1), BreezeIntFmt.L.U, BreezeIntFmt.W.U)
                            io.ctrl.opMod := rs2(0)
                        }
                    }
                    is("b11100".U) { // FMV.X.{W,D} / FCLASS.{S,D}
                        when(rs2 === 0.U && funct3 === 0.U) {
                            accept()
                            io.ctrl.localOp := BreezeFpLocalOp.FMV_X.U
                            io.ctrl.usesFpr1 := true.B
                            io.ctrl.writesGpr := true.B
                            io.ctrl.isDouble := fmt === 1.U
                        }.elsewhen(rs2 === 0.U && funct3 === 1.U) {
                            arithmetic(BreezeFpOp.CLASSIFY, 1)
                            io.ctrl.writesFpr := false.B
                            io.ctrl.writesGpr := true.B
                            io.ctrl.usesArchitecturalRm := false.B
                            io.ctrl.writeFlags := false.B
                        }
                    }
                    is("b11110".U) { // FMV.{W,D}.X
                        when(rs2 === 0.U && funct3 === 0.U) {
                            accept()
                            io.ctrl.localOp := BreezeFpLocalOp.FMV_F.U
                            io.ctrl.usesGpr1 := true.B
                            io.ctrl.writesFpr := true.B
                            io.ctrl.isDouble := fmt === 1.U
                        }
                    }
                }
            }
        }
    }
}

class BreezeFpRegFile extends Module {
    val io = IO(new Bundle {
        val rs1Addr = Input(UInt(5.W))
        val rs2Addr = Input(UInt(5.W))
        val rs3Addr = Input(UInt(5.W))
        val rs1Data = Output(UInt(64.W))
        val rs2Data = Output(UInt(64.W))
        val rs3Data = Output(UInt(64.W))
        val rdAddr = Input(UInt(5.W))
        val rdData = Input(UInt(64.W))
        val rdEn = Input(Bool())
    })
    val content = RegInit(VecInit(Seq.fill(32)(0.U(64.W))))
    def read(addr: UInt): UInt = Mux(io.rdEn && io.rdAddr === addr, io.rdData, content(addr))
    io.rs1Data := read(io.rs1Addr)
    io.rs2Data := read(io.rs2Addr)
    io.rs3Data := read(io.rs3Addr)
    when(io.rdEn) { content(io.rdAddr) := io.rdData }
}

/** Resolve the vendored FPnew sources once at elaboration time.  ChiselSim
  * only sees BlackBox annotations; the cluster generator has its own explicit
  * filelist for LiteX.  Keeping the list in the checked-in manifest makes the
  * two paths consume exactly the same CVFPU source set.
  */
object BreezeFpSources {
    private def findDesignRoot(): Path = {
        val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize
        Iterator.iterate(cwd)(_.getParent).takeWhile(_ != null).flatMap { base =>
            Seq(base, base.resolve("design"))
        }.find(candidate =>
            Files.isRegularFile(candidate.resolve(
                "src/main/resources/vsrc/fpnew/cvfpu-files.f")))
          .getOrElse(throw new IllegalStateException(
              s"cannot locate Flow design root from $cwd"))
    }

    val designRoot: Path = findDesignRoot()
    val flowRoot: Path = designRoot.getParent
    val cvfpuRoot: Path = flowRoot.resolve("third_party/cvfpu")
    val manifest: Path = designRoot.resolve(
        "src/main/resources/vsrc/fpnew/cvfpu-files.f")
    val wrapper: Path = designRoot.resolve(
        "src/main/resources/vsrc/fpnew/FlowFpnewWrapper.sv")
    val includeDir: Path = cvfpuRoot.resolve("src/common_cells/include")

    require(Files.isDirectory(cvfpuRoot), s"CVFPU submodule is missing: $cvfpuRoot")
    require(Files.isRegularFile(wrapper), s"FPnew wrapper is missing: $wrapper")
    require(Files.isDirectory(includeDir), s"CVFPU include directory is missing: $includeDir")

    val sourceFiles: Seq[Path] = {
        import scala.jdk.CollectionConverters._
        Files.readAllLines(manifest).asScala.map(_.trim).filter(line =>
            line.nonEmpty && !line.startsWith("#") && !line.startsWith("+incdir+")
        ).map { relative =>
            val path = cvfpuRoot.resolve(relative).normalize
            require(Files.isRegularFile(path), s"CVFPU source is missing: $path")
            path
        }.toSeq :+ wrapper
    }
}

class FlowFpnewBlackBox extends BlackBox with HasBlackBoxPath {
    override def desiredName = "FlowFpnewWrapper"
    BreezeFpSources.sourceFiles.foreach(path => addPath(path.toString))
    val io = IO(new Bundle {
        val clk_i = Input(Clock())
        val reset_i = Input(Bool())
        val flush_i = Input(Bool())
        val in_valid_i = Input(Bool())
        val in_ready_o = Output(Bool())
        val operand_a_i = Input(UInt(64.W))
        val operand_b_i = Input(UInt(64.W))
        val operand_c_i = Input(UInt(64.W))
        val rnd_mode_i = Input(UInt(3.W))
        val op_i = Input(UInt(4.W))
        val op_mod_i = Input(Bool())
        val src_fmt_i = Input(UInt(3.W))
        val dst_fmt_i = Input(UInt(3.W))
        val int_fmt_i = Input(UInt(2.W))
        val out_valid_o = Output(Bool())
        val out_ready_i = Input(Bool())
        val result_o = Output(UInt(64.W))
        val status_o = Output(UInt(5.W))
        val busy_o = Output(Bool())
    })
}

class BreezeFpUnit extends Module {
    val io = IO(new Bundle {
        val flush = Input(Bool())
        val inValid = Input(Bool())
        val inReady = Output(Bool())
        val operandA = Input(UInt(64.W))
        val operandB = Input(UInt(64.W))
        val operandC = Input(UInt(64.W))
        val rm = Input(UInt(3.W))
        val operation = Input(UInt(4.W))
        val opMod = Input(Bool())
        val srcFmt = Input(UInt(3.W))
        val dstFmt = Input(UInt(3.W))
        val intFmt = Input(UInt(2.W))
        val outValid = Output(Bool())
        val result = Output(UInt(64.W))
        val status = Output(UInt(5.W))
        val busy = Output(Bool())
    })
    val impl = Module(new FlowFpnewBlackBox)
    impl.io.clk_i := clock
    impl.io.reset_i := reset.asBool
    impl.io.flush_i := io.flush
    impl.io.in_valid_i := io.inValid
    io.inReady := impl.io.in_ready_o
    impl.io.operand_a_i := io.operandA
    impl.io.operand_b_i := io.operandB
    impl.io.operand_c_i := io.operandC
    impl.io.rnd_mode_i := io.rm
    impl.io.op_i := io.operation
    impl.io.op_mod_i := io.opMod
    impl.io.src_fmt_i := io.srcFmt
    impl.io.dst_fmt_i := io.dstFmt
    impl.io.int_fmt_i := io.intFmt
    impl.io.out_ready_i := true.B
    io.outValid := impl.io.out_valid_o
    io.result := impl.io.result_o
    io.status := impl.io.status_o
    io.busy := impl.io.busy_o
}
