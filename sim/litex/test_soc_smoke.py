"""The smoke oracle must recognize actual UART and reject build-only output."""
import importlib.util
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location('soc_smoke', Path(__file__).with_name('run_soc_smoke.py'))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)

BANNER = 'Build your hardware, easily!\n(c) Copyright 2007-2015 M-Labs\n'


@pytest.mark.parametrize('prompt', ['litex> ', '\x1b[92;1mlitex\x1b[0m> '])
def test_uart_banner_memtest_and_console_without_build_timestamp(prompt):
    assert smoke.bios_console_ready(BANNER + 'Memtest OK\n' + prompt)


@pytest.mark.parametrize('output', [
    'INFO:SoC: Build your hardware, easily!\nMemtest OK\nlitex>',
    BANNER + 'Memtest at 0x80000000 (64.0KiB)...\nlitex>',
    BANNER + 'Memtest OK\n',
])
def test_build_output_or_incomplete_firmware_cannot_pass(output):
    assert not smoke.bios_console_ready(output)
