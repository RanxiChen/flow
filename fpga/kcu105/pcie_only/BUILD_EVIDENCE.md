# PCIe-only r1 build evidence

Date: 2026-09-23

## Verified build

- Vivado: 2022.2
- Part: `xcku040-ffva1156-2-e`
- Build host directory:
  `/home/chen/FUN/flow/build/fpga/kcu105-pcie-only-r1-link-ila-dev2`
- Synthesis: `synth_design Complete!`
- Implementation: `write_bitstream Complete!`
- Post-route WNS: `+0.127 ns`
- Post-route WHS: `+0.013 ns`
- Failed routed nets: 0
- Bitstream SHA-256:
  `a53f6e2ad7c6a9961dcba05dae47a4a0c4b6eef5156cd19dff73575209b897c1`
- LTX SHA-256:
  `512091e67b6c92ed13a1339e32ac20040e077456a998189ac4ba1529c35504dd`

The directory contains `pcie_only.bit`, `pcie_only.ltx`, `build-status.txt`,
`timing_summary.rpt`, `cdc.rpt`, `drc.rpt`, `utilization.rpt`, hash files, the
Vivado project, and the exact RTL/Tcl/XDC source snapshot used for this build.

## Checks and warning classification

- Local directed Icarus test: `PCIE_ONLY_DECERR_PASS`.
- Timing report: zero setup and hold failing endpoints.
- Custom top-level crossings (`sync_perst`, `sync_perst_to_axi`,
  `sync_refclk_toggle`, `sync_axi_toggle`, `sync_axi_reset`, `sync_link_up`)
  are reported as `CDC-3 Info`, using XPM synchronizers with `ASYNC_REG`.
- All two `CDC-6` and all 38 `CDC-15` warnings are inside generated ILA or
  debug-hub hierarchy, not the custom PCIe-only top-level CDC paths.
- DRC has zero errors and five warnings: one missing board configuration
  voltage declaration, three generated debug-hub LUT warnings, and one
  generated debug/XDMA no-routable-load summary.  The configuration-voltage
  warning is not silently waived; board configuration voltage should be
  confirmed before declaring this report fully clean.
- `pcie_x8_rst_n` intentionally has no synchronous input delay because it is
  an asynchronous PCIe reset input and only enters XDMA/XPM reset observation
  paths.  The timing report therefore retains one `no_input_delay` notice.

## Failed precursor retained as evidence

The first isolated build directory was
`/home/chen/FUN/flow/build/fpga/kcu105-pcie-only-r1-link-ila-dev1`.
Synthesis and routing ran, but bitstream generation was correctly rejected by
DRC `REQP-1848`: `IBUFDS_GTE3.ODIV2` was also driving an ordinary fabric
heartbeat counter directly.  The final RTL adds a `BUFG_GT` only on the
observation branch.  This failure is not a board result and the rejected
artifact must never be programmed.

## Evidence boundary

The final artifact is generated and timing-clean.  It has not been programmed
in this task.  There is no r1 ILA capture and no r1 Host enumeration evidence
yet; therefore this file does not claim that the physical PCIe link works.
