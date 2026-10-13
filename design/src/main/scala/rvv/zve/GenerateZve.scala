package flow.rvv.zve

/** Standalone RTL at the default parameters (512-bit VRF, 16 conversions,
  * 8 FMA lanes) with external scalar-FPU/translation/memory-order services.
  * No debug VRF port. The SoC instance uses ZveParams.soc (256-bit rows). */
object GenerateZve extends App {
  require(args.length<=1,"usage: runMain flow.rvv.zve.GenerateZve [output-directory]")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(new ZveCoprocessor(),
    Array("--target-dir",args.headOption.getOrElse("generated/rvv-zve32f")),
    Array("-disable-all-randomization","-strip-debug-info"))
}
