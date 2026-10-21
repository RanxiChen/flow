"""Check the generated SBI binding against the public valid-ID set."""
import json
import re
import unittest
from generate_frontend_observe_catalog import outputs


class CatalogBindingTest(unittest.TestCase):
    def test_exact_coverage_without_invalid_aliases_and_within_firmware_capacity(self):
        generated = outputs()
        catalog = json.loads(generated['docs/frontend-observe-events.json'])
        dts = generated['linux/buildroot-external/board/flow/dts/flow/flow-kcu105-tiny-perf.dts']
        raw = dts.split('riscv,raw-event-to-mhpmcounters =',1)[1].split(';',1)[0]
        rows = [tuple(int(x,16) for x in re.findall(r'0x[0-9a-f]+',r))
                for r in re.findall(r'<([^>]+)>',raw)]
        self.assertLess(len(rows)+2,256)
        valid = set(range(1,21)) | {e['raw'] for e in catalog['events']}
        for value in range(max(valid)+257):
            matched = sum(value & ((mh<<32)|ml) == (vh<<32)|vl for vh,vl,mh,ml,counters in rows)
            self.assertEqual(matched, int(value in valid),hex(value))
        for e in valid:
            for high in (32,48,55,63):
                bad=e|(1<<high)
                self.assertFalse(any(bad&((mh<<32)|ml) == (vh<<32)|vl for vh,vl,mh,ml,c in rows))
        self.assertTrue(all(c==0x7f8 for vh,vl,mh,ml,c in rows))
        self.assertIn('<0x1 0x0 0x10fa>, <0x2 0x0 0x10fb>',dts)


if __name__ == '__main__':
    unittest.main()
