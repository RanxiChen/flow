# Breeze perf image

Single-hart scalar initramfs with perf, OpenSBI PMU mappings and self-checking
sampling workloads. Requires matching RTL with the Breeze Sscofpmf changes;
older released bitstreams do not provide this sampling interface.

```sh
BUILDROOT_DIR=/path/to/buildroot bash linux/sd-perf/build.sh
```

Outputs: `build/sd-perf/output` and `build/sd-perf/boot`.
Use the entire boot directory, including its new boot.json and perf DTB.
Eight programmable counters are available. Generic `cycles`/`instructions`
use HPM3..10, including mode filtering and overflow interrupts. Fixed counter0/2
remain architectural counters. Legacy raw IDs 1..20 and the appended frontend
observation IDs are listed in `docs/frontend-observe-events.json`.

After booting the matching hardware and image, run as root:

```sh
perf stat -e cycles,instructions -- /bin/true
flow-perf-smoke /tmp/flow-perf
```

The smoke checks workload checksums, records user cycles/instructions with
frame-pointer call graphs and records raw I-cache demand misses (`r1030:u`)
on a 32 KiB scalar text sweep. It requires nonempty samples and the expected
hot symbols in `perf report`; success prints `FLOW_PERF_SAMPLING_PASS`.
Outputs include perf.data, reports, samples, CPU identity and perf version.
Copy them from `/tmp` before rebooting. The workloads retain symbols because
this profile excludes `flow-perf-workload` from stripping; own programs should retain symbols and
use `-fno-omit-frame-pointer` when requesting frame-pointer call graphs.

Sampling PC is the interrupt context and can skid past the triggering event;
this is not precise instruction-address sampling. The address observation
trace and total counters remain separate tools for frontend analysis.
Whole-process GAPBS statistics include graph generation and construction.

Implementation and separately scoped verification status:
[`docs/tasks/perf-sampling-20261020.md`](../../docs/tasks/perf-sampling-20261020.md).
