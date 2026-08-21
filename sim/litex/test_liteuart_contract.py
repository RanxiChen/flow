#!/usr/bin/env python3
"""Keep the SoC, DTS and Buildroot LiteUART contract synchronized."""

import json
import os
import unittest


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def read_text(relative_path):
    with open(os.path.join(FLOW_ROOT, relative_path), encoding="utf-8") as handle:
        return handle.read()


class LiteUartContractTest(unittest.TestCase):
    def test_platform_uses_native_liteuart_page(self):
        with open(os.path.join(FLOW_ROOT, "config", "breeze_mcu_platform.json"),
                  encoding="utf-8") as handle:
            platform = json.load(handle)
        pages = {page["name"]: int(page["origin"], 0)
                 for page in platform["mmioPages"]}
        regions = {region["name"] for region in platform["regions"]}
        self.assertEqual(pages["uart"], 0x12001000)
        self.assertNotIn("linux_uart16550", regions)

    def test_both_device_trees_select_liteuart(self):
        for path in (
                "software/breeze-linux/flow-small.dts",
                "linux/buildroot-external/board/flow/dts/flow/flow-small.dts"):
            text = read_text(path)
            self.assertIn('compatible = "litex,liteuart";', text, path)
            self.assertIn("serial@12001000", text, path)
            self.assertIn("interrupts = <10>;", text, path)
            self.assertNotIn("ns16550", text, path)
            self.assertNotIn("13000000", text, path)

    def test_linux_and_getty_configuration_match_driver_names(self):
        fragment = read_text("linux/buildroot-external/board/flow/linux.fragment")
        defconfig = read_text("linux/buildroot-external/configs/flow_small_defconfig")
        self.assertIn("CONFIG_LITEX=y", fragment)
        self.assertIn("CONFIG_LITEX_SOC_CONTROLLER=y", fragment)
        self.assertIn("CONFIG_SERIAL_LITEUART=y", fragment)
        self.assertIn("CONFIG_SERIAL_LITEUART_CONSOLE=y", fragment)
        self.assertIn('BR2_TARGET_GENERIC_GETTY_PORT="ttyLXU0"', defconfig)


if __name__ == "__main__":
    unittest.main()
