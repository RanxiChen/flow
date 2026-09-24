# KCU105 PCIe-only link bring-up

This target isolates the KCU105 PCIe edge connector and Xilinx XDMA core from
the Breeze CPU, FASE, DDR, SD, Wishbone, and the SoC reset tree.  Its first
artifact answers only whether the host supplies a usable reference clock and
PERST#, and whether the XDMA LTSSM reaches L0.

## r1 hardware boundary

- Xilinx XDMA 4.1, Gen3 x8, device `10ee:9038`.
- Xilinx ILA 6.2 observed through the normal Vivado JTAG debug hub.
- A 125 MHz boot ILA records synchronized reset and clock-heartbeat state.
- An XDMA user-clock ILA records LTSSM, link width/speed, errors, and accidental
  AXI traffic.
- Protocol-correct AXI4 and AXI4-Lite slaves return DECERR.  r1 deliberately
  provides no BAR register ABI and no DMA memory target.

There is no FASE USER JTAG mailbox in this design.  JTAG is observation-only.

For a guided Chinese lesson, give another agent this directory and ask it to
follow `TEACHING_PROMPT.md`.  The longer `DESIGN_TUTORIAL.md` is the technical
source of truth and includes explicit evidence boundaries.

## Clock-domain rule

LTSSM and other multi-bit PCIe state remain in `axi_aclk`.  Only single-bit
heartbeats and status flags cross into the free-running 125 MHz observation
domain, through Xilinx XPM single-bit synchronizers.

## Build

Use a fresh absolute directory on the Vivado host:

```sh
bash fpga/kcu105/pcie_only/build.sh \
  /home/chen/FUN/flow/build/fpga/kcu105-pcie-only-r1-link-ila
```

The build never programs hardware.  A completed build must contain the bitstream,
debug probes, timing, CDC, DRC, utilization, source commit, dirty-tree snapshot,
and SHA-256 identity.

The verified 2026-09-23 build result and its exact evidence boundary are in
`BUILD_EVIDENCE.md`.  It is implementation-complete but not board-validated.

## Board capture order

1. Keep the host powered off.
2. Power the KCU105 and program the r1 bitstream.
3. Arm both ILAs over JTAG.
4. Power on the host.
5. Export the captures before changing power or reset state.

The helper Tcl files under `capture/` implement steps 2--3 and step 5.  They
never control host power.  On the JTAG machine, use:

```sh
vivado -mode batch -source capture/program_and_arm.tcl \
  -tclargs /absolute/pcie_only.bit /absolute/pcie_only.ltx
# Power on the host only after FLOW_PCIE_ONLY_ARMED is printed.
vivado -mode batch -source capture/export_capture.tcl \
  -tclargs /absolute/new-capture-directory
```

`program_and_arm.tcl` triggers both ILAs on the rising edge of PERST#.  If the
link ILA never acquires because `axi_aclk` never starts, that absence is itself
evidence; use the independently clocked boot ILA to distinguish REFCLK/PERST#
from XDMA-user-clock failure.

Enumeration, link speed/width, driver binding, BAR access, and DMA are separate
gates.  r1 is complete only through link/enumeration.  A later r2 will add a
PCIe-only status BAR, on-chip test RAM, and AXI request/response counters.
