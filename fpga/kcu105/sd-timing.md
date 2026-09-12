# SD timing model for the 400 kHz / 5 MHz bring-up profile

The official PHY has sys-clocked FDCE input/output registers. The command and
data receiver consumes the sampled inputs only at data_i_ce. This is not a
separate asynchronous SD-clock domain and its FIFO cannot repair a bad sample.
A generated 5 MHz clock alone would incorrectly model the continuously
sys-clocked input register as a 5 MHz register. This implementation instead
bounds actual IO transport and checks the protocol budget separately.

`sd_timing.tcl` discovers only the five first input registers and the final
CLK/CMD/DAT output and output-enable registers through connectivity. It fails
if the topology does not match. Both directions get a 10 ns transport budget.
No Q-pin exceptions, all-register exceptions, false paths, or internal
multicycle exceptions are introduced. The 10 ns value is an allocated FPGA
routing budget, not a claimed SD-card or PCB measurement. Routed reports
sd_input_paths.rpt / sd_output_paths.rpt must meet it.

Conservative external budget inputs (ns):

- SD default-speed card: setup/hold 5/5, data output max 14; identification
  output max 50. SD Association Physical Layer Simplified Specification,
  default bus timing. Use a conservative 6 ns input setup budget to also cover
  the driver's high-speed switch; clock remains capped at 5 MHz.
- U107 FSSD07: allocate 10 ns each for clock and data propagation (rounded up
  from the published 6 ns clock / 9 ns data maxima). This bound assumes the
  datasheet voltage/load conditions; it is not measured on this board.
- Allocate 2 ns PCB propagation per leg and 5 ns extra sampling uncertainty.
  PCB/load bounds are engineering assumptions, not extracted board timing.
- Reserve two sys cycles from a half SD period for the PHY IO/CE pipeline.

A directed simulation of the installed official clocker and command writer,
with the same one-stage SDR output flops, shows command data changes ONE SYS
CYCLE AFTER the SD rising edge (not on the falling edge). This is upstream
behavior, not a local modification. At 100 MHz / 5 MHz, next-edge setup has
roughly 200 - 10 ns before external delays; hold has only 10 ns before skew.

Transmit setup lower bound using the allocated limits: 200 - 10 - 10 (FPGA
output) - 10 (mux) - 2 (PCB) - 6 (card setup) = 162 ns. Identification has
more setup time. Transmit hold must instead be checked as:

    10 + data_path_min - clock_path_max - card_hold

The independent conservative external bounds above do NOT establish a
positive hold lower bound. A slower SD divider does not increase this 10 ns
hold interval. Do not claim external hold is closed or change the official
PHY merely because that conservative bound fails. This version produces
routed max/min path reports and adds detailed ILA observation so that actual
path skew and behavior can be investigated. No SD clock polarity is changed.

Receive setup likewise depends on the exact input-flop/CE relationship;
input transport bounds alone are not a complete receiver setup/hold proof.
The remaining external timing closure is explicitly OPEN, even if the IO
routing budgets and all internal synchronous paths pass.

These calculations depend on the official PHY's edge/CE relationship and
stable divider during a transfer. The scheduler check is in check_sd_scheduler.py. They do not prove SI, actual PCB delays, card power, or mux ownership.
Do NOT describe the ordinary no_input_delay/no_output_delay counts as zero:
this is a documented IO transport + protocol budget model, not a complete
external device model expressed with set_input_delay/set_output_delay.

References:
- https://docs.amd.com/v/u/en-US/ug917-kcu105-eval-bd (Figure 1-7)
- https://www.sdcard.org/downloads/pls/ (Physical Layer simplified spec)
- https://www.mouser.com/datasheet/2/149/FSSD07-83322.pdf (manufacturer datasheet)
- https://docs.amd.com/r/2022.2-English/ug835-vivado-tcl-commands/set_max_delay

No statement here proves that SD initialization currently fails due to timing.
