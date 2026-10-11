package flow.memsys

import chisel3._

/** Observe the proposed R7 sampling boundary around the production L1D.
  * CPU requests are idle; PTW is still an independent, legal requester.
  * This does not implement BE2 or assume that a sampled condition is safe.
  */
class BE2SerialConditionHarness extends L1DPermissionHarness {
  val sampledDrained = IO(Output(Bool()))
  sampledDrained := RegNext(cache.io.core.drained, false.B)
}

/** A source-contract audit, not a BE2 acceptance test. Passing reproduces a
  * counterexample to treating current drained as monotonic under CPU silence.
  */
class BE2SerialConditionSpec extends L1DPermissionsSpec {
  "B3 audit: a previously accepted PTW miss invalidates sampled drained with no CPU requests" in {
    simulate(new BE2SerialConditionHarness) { d =>
      init(d)
      d.io.core.req.valid.expect(false.B)
      d.io.core.drained.expect(true.B)
      d.sampledDrained.expect(true.B)
      // This is the physical PTE service used by Sv39Ptw. The line is not
      // resident. PTE accesses run independently of the CPU serial owner.
      d.io.ptw.req.bits.paddr.poke(BigInt("80000000", 16).U)
      d.io.ptw.req.valid.poke(true.B)
      d.io.ptw.req.ready.expect(true.B)
      d.clock.step() // S1
      d.io.ptw.req.valid.poke(false.B)
      d.clock.step() // S2
      d.clock.step() // S3: miss allocates at the following edge
      d.io.core.req.valid.expect(false.B)
      d.io.core.drained.expect(true.B)
      d.sampledDrained.expect(true.B)
      d.io.coherenceRequest.expect(false.B)
      println("BE2_B3 cycle=D CPU_idle=1 PTW=S3 raw_drained=1 sampled_drained=1")
      d.clock.step()
      d.io.core.req.valid.expect(false.B)
      d.io.core.drained.expect(false.B)
      d.sampledDrained.expect(true.B)
      d.io.coherenceRequest.expect(true.B)
      println("BE2_B3 cycle=D+1 CPU_idle=1 PTW=MSHR raw_drained=0 sampled_drained=1")
      // Delaying CPU issue cannot prevent this transition: the PTW request
      // was accepted three edges earlier, before sampling the ready bit.
    }
  }
}
