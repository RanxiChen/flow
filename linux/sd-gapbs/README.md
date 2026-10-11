# Breeze SD-loaded GAPBS and Python

This profile retains musl Buildroot, adds CoreMark, C++/OpenMP, GAPBS and basic Python 3,
and embeds the root filesystem in the Linux Image. LiteX BIOS reads the boot
files from FAT; Linux does not need the SD controller to mount its root.

Build separately from the SD-root profile and the future sd-full profile:

```sh
BUILDROOT_DIR=/path/to/buildroot bash linux/sd-gapbs/build.sh
```

Default outputs are `build/sd-gapbs/output` and `build/sd-gapbs/boot`.
Set BUILDROOT_OUT and SD_GAPBS_BOOT to change these paths. Copy the boot directory
files to the FAT partition after checking their hashes. This also replaces the
boot.json and DTB selection: do not retain the SD-root boot.json. No FPGA rebuild
or ext4 repartitioning is needed. The script never writes a block device.

After login run `gapbs-smoke`. This runs Python and six GAPBS kernels with
`-g 10 -n 1 -v`; inspect their verification results. GAPBS is pinned to commit
b5e3e19c2845f22fb338f4a4bc4b1ccee861d026, matching the source found on Alan.
The provenance of the old Rocket binaries is not established by that fact.
For measurement, set OMP_NUM_THREADS=1 and OMP_DYNAMIC=FALSE, then run the
recorded Rocket commands from /opt/gapbs. Preserve real/user/sys and GAPBS
Average Time separately. Graph generation and process startup are included
in shell time but not necessarily in GAPBS Average Time. Compiler/libc changes
and unknown Rocket clock frequency limit direct hardware comparisons.

All runtime files are in RAM. Save console output on the host; results disappear
at power-off. No Python third-party packages or network support are added.

CoreMark 1.01 is installed as `/usr/bin/coremark`, built with `-O3` for the
scalar ISA. Run `coremark` manually after boot and preserve its validation,
iterations, elapsed time and printed compiler flags. It is not run during boot.
Its license is included in `/usr/share/licenses/coremark/`.

To add CoreMark while preserving all existing GAPBS/rootfs file data, use an
existing validated GAPBS SDK and kernel build in a fresh output directory:

```sh
JOBS=2 bash linux/sd-gapbs/rebuild-coremark-gapbs.sh \
  /path/to/gapbs-sdk-output /path/to/coremark-1.01.tar.gz \
  /path/to/validated-kernel-parent /absolute/fresh/output
```

This verifies the pinned release archive, compiles the Linux binary, appends
a newc archive to the unchanged GAPBS cpio, checks every original file's data
and all nine GAPBS ELF hashes, then relinks a separate kernel build output.
The three BE2 from-source Buildroot profiles also enable Flow CoreMark.
