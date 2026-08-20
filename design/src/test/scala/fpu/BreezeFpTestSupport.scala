package flow.fpu

import chisel3.simulator.scalatest.ChiselSim
import svsim.{CommonCompilationSettings, CommonSettingsModifications}

/** Add CVFPU's header directory to every ChiselSim compilation that contains
  * BreezeFpUnit.  The Verilog source files themselves come from the
  * HasBlackBoxPath annotations on FlowFpnewBlackBox.
  */
trait BreezeFpChiselSim extends ChiselSim {
  abstract override implicit def commonSettingsModifications: CommonSettingsModifications = {
    val inherited = super.commonSettingsModifications
    (settings: CommonCompilationSettings) => {
      val base = inherited(settings)
      val include = BreezeFpSources.includeDir.toString
      val includes = base.includeDirs.getOrElse(Seq.empty)
      base.copy(includeDirs = Some((includes :+ include).distinct))
    }
  }
}
