"""Public build menus reject retired variants before creating hardware outputs."""
from pathlib import Path
import importlib.util
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'litex_wrapper'))
from flow.cluster import CORE_PRESETS, FlowCluster


class PublicBuildOptionsTest(unittest.TestCase):
    def test_collector_keeps_base_configuration_checks_for_the_zve_product(self):
        spec = importlib.util.spec_from_file_location('collect_sd_products',
            ROOT / 'fpga/kcu105/collect_sd_products.py')
        collector = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(collector)
        scalar = {'profile': 'single', 'nCores': '1', 'preset': 'gshare',
                  'privilege': 'linux', 'l2Slots': '2', 'memoryBits': '64', 'rvv': 'false'}
        vector = dict(scalar, profile='single-zve32f', zve='zve32f', vlen='512', vectorL2Ids='8')
        configs = {'single': scalar, 'four': dict(scalar, profile='small', nCores='4'),
                   'single-zve32f': vector}
        collector.validate_product_configs(configs)
        for key, value in (('l2Slots', '4'), ('preset', 'baseline'), ('memoryBits', '512'),
                           ('zve', 'missing'), ('rvv', 'true')):
            with self.subTest(key=key), self.assertRaises(ValueError):
                collector.validate_product_configs(dict(configs,
                    **{'single-zve32f': dict(vector, **{key: value})}))

    def test_legacy_simulation_wrapper_only_accepts_gshare_linux(self):
        self.assertEqual(CORE_PRESETS, ('gshare',))
        self.assertEqual(FlowCluster.privilege_profile, 'linux')
        with self.assertRaises(ValueError):
            FlowCluster.set_cluster_config('single', 'baseline', 'linux')
        with self.assertRaises(ValueError):
            FlowCluster.set_cluster_config('single', 'gshare', 'mcu')

    def test_fpga_and_sd_help_only_advertise_current_vector_product(self):
        for script in ('target.py', 'prepare_sd_boot.py', 'collect_sd_products.py'):
            with self.subTest(script=script):
                result = subprocess.run([sys.executable, str(ROOT / 'fpga/kcu105' / script), '--help'],
                                        text=True, capture_output=True, check=True)
                self.assertNotIn('single-rvv', result.stdout)
                self.assertNotIn('breeze-rvv', result.stdout)
                self.assertIn('zve32f', result.stdout)

    def test_retired_vector_choices_fail_without_creating_output_directories(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'output'
            commands = (
                [sys.executable, str(ROOT / 'fpga/kcu105/target.py'), '--cpu-type', 'breeze-rvv',
                 '--output-dir', str(output)],
                [sys.executable, str(ROOT / 'fpga/kcu105/prepare_sd_boot.py'), '--profile', 'single-rvv',
                 '--images', str(Path(tmp) / 'images'), '--output', str(output)],
                ['bash', str(ROOT / 'fpga/kcu105/build_sd.sh'), 'single-rvv', str(output)],
            )
            for command in commands:
                with self.subTest(command=command):
                    result = subprocess.run(command, text=True, capture_output=True)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
