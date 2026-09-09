"""Deprecated configurable wrappers retained for existing simulations.

New FPGA targets must import :class:`flow.core.Breeze`. This module only
keeps the historical MCU/Linux simulation entry points while they are being
retired; CPU wiring and RTL loading now live in ``core.py``.
"""

from .core import (
    DCACHE_TRACE_LAYOUT,
    DCACHE_TRACE_RTL_NAMES,
    RETIRE_LAYOUT,
    RETIRE_RTL_NAMES,
    _BreezeClusterCPU,
)


CLUSTER_PROFILES = ("single", "dual", "small")
CLUSTER_NUM_HARTS = {"single": 1, "dual": 2, "small": 4}
CLUSTER_L2_BYTES = {"single": 16384, "dual": 32768, "small": 65536}
CORE_PRESETS = ("baseline", "gshare")


class FlowCluster(_BreezeClusterCPU):
    """Legacy dynamically selected cluster used only by simulation runners."""

    name = "flow_cluster"
    human_name = "Legacy Flow cluster"
    variants = ("minimal",)
    cluster_profile = "single"
    num_harts = 1
    l2_bytes = CLUSTER_L2_BYTES["single"]
    core_preset = "gshare"
    privilege_profile = "mcu"
    rtl_mode = "debug"
    tandem_enabled = True
    gcc_arch = "rv64i2p0"
    gcc_abi = "lp64"
    gcc_defines = "-D__flow__"
    reset_vector = 0x1000_0000
    expected_marker = {
        "rtlMode": "debug",
        "tandem": "true",
    }
    mem_map = {
        "rom": reset_vector,
        "sram": 0x1100_0000,
        "csr": 0x1200_0000,
        "main_ram": 0x8000_0000,
    }

    def __init__(self, platform, variant="minimal"):
        super().__init__(platform, variant)

    @classmethod
    def set_cluster_config(
            cls, cluster_profile, core_preset, privilege_profile="mcu"):
        if cluster_profile not in CLUSTER_PROFILES:
            raise ValueError(
                f"Unsupported cluster profile {cluster_profile!r}; "
                f"expected {' or '.join(CLUSTER_PROFILES)}")
        if core_preset not in CORE_PRESETS:
            raise ValueError(
                f"Unsupported core preset {core_preset!r}; "
                f"expected {' or '.join(CORE_PRESETS)}")
        if privilege_profile not in ("mcu", "linux"):
            raise ValueError("privilege profile must be 'mcu' or 'linux'")

        cls.cluster_profile = cluster_profile
        cls.num_harts = CLUSTER_NUM_HARTS[cluster_profile]
        cls.l2_bytes = CLUSTER_L2_BYTES[cluster_profile]
        cls.core_preset = core_preset
        cls.privilege_profile = privilege_profile
        cls.rtl_mode = "debug"
        cls.tandem_enabled = True
        cls.reset_vector = (
            0x1001_0000 if privilege_profile == "linux" else 0x1000_0000)
        cls.mem_map = {
            "rom": cls.reset_vector,
            "sram": 0x1100_0000,
            "csr": 0x1200_0000,
            "main_ram": 0x8000_0000,
        }
        cls.expected_marker = {
            "rtlMode": "debug",
            "tandem": "true",
        }


class Flow(FlowCluster):
    """Legacy single-hart CPU name used by the original LiteX simulator."""

    name = "flow"
    human_name = "Legacy Flow single-hart simulation CPU"
    core_presets = CORE_PRESETS

    @classmethod
    def set_core_preset(cls, core_preset):
        cls.set_cluster_config("single", core_preset, "mcu")
