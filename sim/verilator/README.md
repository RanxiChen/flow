# CVFPU compatibility with Verilator 5.028

The pinned CVFPU uses disjoint elements of packed stage arrays for continuous
input connections and sequential pipeline registers. Verilator 5.028 reports
BLKANDNBLK for these arrays. `cvfpu.vlt` scopes the exception to FPnew source
files, retaining the check elsewhere. It does not change synthesized RTL.

For ChiselSim and LiteX simulations using this version, set the real executable
before adding the shim to PATH (from the repository root):

```sh
export FLOW_REAL_VERILATOR=$(command -v verilator)
export PATH="$PWD/sim/verilator/compat:$PATH"
```

Use an absolute path for FLOW_REAL_VERILATOR; do not point it at the shim.
The simulation command logged by ChiselSim/LiteX then includes this wrapper.
