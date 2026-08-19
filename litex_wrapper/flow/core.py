"""Legacy single-core LiteX CPU entry.

The dedicated single-core top (BreezeCoreWishbone) was deleted: the Flow CPU
is now simply the single-profile Breeze cluster (one hart whose D$ and I$
refills go through the shared L2/Home, exposing one memory Wishbone master
and one MMIO Wishbone master). This shim keeps the historical `flow` CPU
name, the `set_core_preset` entry point and the `core_presets` attribute so
existing runners stay source-compatible, while all RTL loading, profile
marker cross-checking and per-hart CLINT wiring live in `flow.cluster`.
"""

from flow.cluster import FlowCluster


class Flow(FlowCluster):
    cluster_profile = "single"
    core_preset     = "gshare"
    name            = "flow"
    human_name      = "Flow"
    core_presets    = ("baseline", "gshare")

    @classmethod
    def set_core_preset(cls, core_preset):
        cls.set_cluster_config("single", core_preset)
