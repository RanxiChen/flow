# Breeze perf image

Separate build of the GAPBS/Python initramfs with perf and an OpenSBI PMU
mapping. Reuses the released single-hart FPGA bitstream; board validation pending.

```sh
BUILDROOT_DIR=/path/to/buildroot bash linux/sd-perf/build.sh
```

Outputs: `build/sd-perf/output` and `build/sd-perf/boot`.
Use the entire boot directory, including its new boot.json and perf DTB.
Raw events 1..10 follow docs/pmu.md; 8 programmable counters are available.
Start with `perf stat -e cycles,instructions -- /bin/true`, then validate raw
events on small workloads. No privilege filtering or overflow sampling is
claimed. Whole-process statistics include graph generation and construction.
