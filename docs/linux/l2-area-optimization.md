# L2 area changes pending validation

Baseline: `6b0d572`, single-hart 100 MHz FPGA implementation. L2/Home used
7,459 LUT and 2,238 FF in the post-place hierarchy report. These are baseline
numbers, not measurements of the changes below.

Two local changes in `BreezeL2Home.scala`:

1. Broadcast registered data/directory write payloads and shared read/write
   addresses to all ways. Only each way's write enable selects a write.
   Directory initialization still clears every way. The shared `flowSRAM`
   implementation and its output behavior are unchanged.
2. Select hit/victim on narrow one-hot controls before one line-wide mux.
   `selectedLineReg` replaces the separate hit/victim data registers. Compare
   captures the selected line; a dirty victim recall replaces it before
   writeback. A clean recall uses the captured array line. Response and array
   write registers remain separate to preserve backpressure and update ordering.

No FSM stage, capacity, associativity, coherence interface or arithmetic unit
changes. The source declares 256 fewer data-register bits for a 32-byte line;
actual FF/LUT/CLB savings and 100 MHz timing are unmeasured. The new hit-dependent
way selection must be checked in implementation for added delay/fanout.

Two new tests in `BreezeL2HomeSpec` cover distinct data across all eight ways,
out-of-order hits, every-way dirty replacement, recalled data under grant
backpressure, and a following clean-owner recall without returned data.
Existing suites also cover writeback/refill errors, coherence and four-hart
behavior. On 2026-09-12, Scala production/test compilation completed, and the
L2 regression began elaborating simulations before being stopped at the user's
request. The regression did not complete; there is no suite PASS result. No
Vivado run has been performed for these edits.

Tests are currently paused at the user's request. When testing is resumed,
the focused command from `design/` is:

```sh
sbt 'testOnly flow.cache.BreezeL2HomeSpec flow.cache.BreezeL2HomeSmallSpec'
```

Run the broader cache/coherence regression before a fresh full-SoC regression
and FPGA implementation. Preserve the existing four-hart baseline output and use
a new output directory for this revision. Compare identical configurations;
the single-hart ILA build and four-hart production build are not area-equivalent.
