# Full single-hart F2 timing comparison, candidate v2

Source SHA `7820bf6eb16eaef40337c97e0a59ac8369b35d79` on branch
`timing/f2-wholecore-v2-20261012` changes two private controls in
`BreezeViptICache`. An otherwise harmless SRAM read now uses registered
`waiting/refillWrite` state instead of same-cycle request acceptance; only
the original `query` grants F0 ownership. Deferred younger history may be
refreshed on any registered F1 candidate while replay remains blocked by
`retryValid`; the final successful F1 candidate still supplies the history
used by replay. This preserves F0/F1/F2 stage counts, two return slots,
same-edge cancellation, miss/refill ownership and external interfaces.

The shared simulation-host configuration was reread before tests and RTL
generation. `cloud_chen@47.96.71.231:22` timed out at both preflights; Alan
passed the tool and resource checks. The frontend, L1I and pair-queue
[test command](../../records/f2-owner-ce-20261012/run-tests-v2.sh) returned 0:
9 XML suites, 48 tests, no failures/errors/skips. See [test results](../../records/f2-wholecore-v2-20261012/tests/console.log)
and [source hashes](../../records/f2-wholecore-v2-20261012/tests/source-sha256.txt).

The full debug/tandem single-hart KU040 100 MHz build on Alan used production
XDC, default synthesis and the production SoC top `xilinx_kcu105`. RTL/BIOS
preparation and Vivado 2022.2 synthesis returned 0. The [build binding](../../records/f2-wholecore-v2-20261012/synth/build-binding.json)
confirms a clean source and matching BIOS ROM. Synthesis WNS was +0.451 ns,
WHS -0.252 ns, WPWS +0.066 ns; the 560 payload-register CEs retained only
the three registered owner startpoints. See the [synthesis report](../../records/f2-wholecore-v2-20261012/synth/xilinx_kcu105_timing_synth.rpt)
and [CE audit](../../records/f2-wholecore-v2-20261012/synth/f2-payload-ce-audit.txt).

The [full implementation runner](../../records/f2-owner-ce-20261012/run-full-route-v2.tcl)
used `Explore` placement/pre-route physical optimization and
`NoTimingRelaxation` routing, matching the alternative implementation of the
earlier F2 owner-CE RTL. It completed with exit 0 and generated the
[bitstream](../../records/f2-wholecore-v2-20261012/route/xilinx_kcu105.bit)
and matching [ILA probes](../../records/f2-wholecore-v2-20261012/route/xilinx_kcu105.ltx).
The production routed gate passed at WNS +0.016 ns, TNS 0, WHS +0.004 ns,
THS 0 and WPWS +0.103 ns, with zero failing endpoints and route errors.
`check_timing` found zero unconstrained internal endpoints; all routed bus-skew
constraints passed with minimum slack +9.373 ns, and the SD I/O transport
budget check passed. Detailed [route timing](../../records/f2-wholecore-v2-20261012/route/routed-generation-gate.rpt),
[route status](../../records/f2-wholecore-v2-20261012/route/xilinx_kcu105_route_status.rpt),
[bus skew](../../records/f2-wholecore-v2-20261012/route/bus-skew.rpt)
and [metadata](../../records/f2-wholecore-v2-20261012/results.json) are retained.

This is a valid alternate bitstream, but the earlier `d4350cbb` candidate is
the preferred deliverable under the same target/constraints/strategy: its
routed WNS is +0.021 ns and it uses 58 fewer LUTs and 26 fewer registers.
Both designs have only +0.004 ns hold margin. The alternate route DCP is
retained on Alan; no board boot/runtime proof has been performed for either.
