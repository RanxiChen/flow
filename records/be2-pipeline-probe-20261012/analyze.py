"""Audit recorded simulation results; does not compile or simulate hardware."""
import argparse
import collections
import csv
import hashlib
import json
import pathlib
import struct
import xml.etree.ElementTree as ET


def symbols(path):
    data = path.read_bytes()
    assert data[:6] == b"\x7fELF\x02\x01"
    shoff = struct.unpack_from("<Q", data, 40)[0]
    size, count = struct.unpack_from("<HH", data, 58)
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * size) for i in range(count)]
    result = {}
    for sec in sections:
        if sec[1] != 2:
            continue
        strings = sections[sec[6]]
        for off in range(sec[4], sec[4] + sec[5], sec[9]):
            name, _, _, _, value, _ = struct.unpack_from("<IBBHQQ", data, off)
            start = strings[4] + name
            result[data[start:data.index(b"\0", start)].decode()] = value
    return result


def differences(values):
    return [b - a for a, b in zip(values, values[1:])]


parser = argparse.ArgumentParser()
parser.add_argument("root", type=pathlib.Path)
args = parser.parse_args()
root = args.root
report = {"source_sha": (root / "source-sha.txt").read_text().strip(),
          "scope": "same deployed core configuration; whole-core programs execute in M/Bare",
          "backend": {}, "whole_core": {}, "xml": []}

for name in ("alu-independent", "alu-dependent", "load-hit-model"):
    rows = list(csv.DictReader((root / f"backend-{name}.csv").open()))
    ids = [int(r["cycle"]) for r in rows if r["event"] == "id"]
    commits = [int(r["cycle"]) for r in rows if r["event"] == "commit"]
    assert len(ids) == len(commits) == 64
    assert differences(ids) == differences(commits) == [1] * 63
    assert [r["pc"] for r in rows if r["event"] == "id"] == [r["pc"] for r in rows if r["event"] == "commit"]
    writes = [(int(r["rd"]), int(r["data"], 16)) for r in rows if r["event"] == "gpr"]
    expected = [(1 if name == "alu-dependent" else 1 + i % 16,
                 1000 + i if name == "load-hit-model" else i + 1) for i in range(64)]
    assert writes == expected
    events = collections.defaultdict(collections.Counter)
    for r in rows:
        events[int(r["cycle"])][r["event"]] += 1
    pending = maximum = 0
    for c in sorted(events):
        pending += events[c]["id"]
        maximum = max(maximum, pending)
        pending -= events[c]["commit"]
    assert pending == 0 and maximum >= 4
    report["backend"][name] = {"retired": 64, "id_gap": 1, "retire_gap": 1,
                                "inclusive_retire_span": commits[-1] - commits[0] + 1,
                                "maximum_pending_id_before_same_cycle_commit": maximum,
                                "functional_scoreboard_pass": True}

for name in ("alu-independent", "alu-dependent", "load-hit", "alu-compressed"):
    syms = symbols(root / f"{name}.elf")
    begin, end = syms["probe_begin"], syms["probe_end"]
    rows = list(csv.DictReader((root / f"cluster-{name}.csv").open()))
    observed = [r for r in rows if r["retire"] == "1" and begin <= int(r["pc"], 16) < end]
    step = 2 if name == "alu-compressed" else 4
    assert len(observed) == 192
    assert [int(r["pc"], 16) for r in observed] == [begin + i * step for i in range(64)] * 3
    for idx, r in enumerate(observed):
        i = idx % 64
        rd = 11 if name == "alu-dependent" else 11 + i % 4
        value = i + 1 if name == "alu-dependent" else (101 + i % 4 if name == "load-hit" else 1 + i // 4)
        if name == "load-hit" and idx == 0:
            # The cold first load commits a miss and writes back later. This
            # CSV does not log late-write payloads; do not read a placeholder
            # as an architectural value. All 128 warm loads are checked below.
            assert r["rd_write"] == "0" and r["load_miss"] == "1" and int(r["rd"], 16) == rd
            continue
        assert r["rd_write"] == "1" and int(r["rd"], 16) == rd and int(r["data"], 16) == value, (name, idx, r)
    warm = observed[64:]
    groups = [warm[:64], warm[64:]]
    spans = [int(g[-1]["cycle"]) - int(g[0]["cycle"]) + 1 for g in groups]
    gaps = [v for g in groups for v in differences([int(r["cycle"]) for r in g])]
    first, last = int(warm[0]["cycle"]), int(warm[-1]["cycle"])
    active = [r for r in rows if first <= int(r["cycle"]) <= last]
    assert not any(int(r[k]) for r in active for k in ("load_miss", "store_miss", "l2_read", "mem_ar", "replay", "fatal"))
    ipc = 128 / sum(spans)
    report["whole_core"][name] = {"functional_pass": True, "warm_retired": 128,
        "window_spans": spans, "warm_ipc": ipc, "retire_gap_histogram": dict(collections.Counter(gaps)),
        "id_gap_histogram_during_warm_extent": dict(collections.Counter(differences(
            [int(r["cycle"]) for r in active if r["id"] == "1"]))),
        "ex_hold_cycles": sum(int(r["ex_hold"]) for r in active),
        "wb_hold_cycles": sum(int(r["wb_hold"]) for r in active),
        "distinct_window_ex_commit_overlap": sum(r["ex"] == r["commit"] == "1" and
            begin <= int(r["ex_pc"], 16) < end and begin <= int(r["commit_pc"], 16) < end and
            r["ex_pc"] != r["commit_pc"] for r in active),
        "no_cache_miss_replay_or_external_memory_in_warm_extent": True,
        "diagnostic_target_0_5_ipc_met": ipc >= 0.5}

for path in sorted(root.glob("TEST-*.xml")):
    x = ET.parse(path).getroot()
    attrs = {k: x.attrib[k] for k in ("name", "tests", "failures", "errors", "skipped")}
    report["xml"].append(attrs)
assert len(report["xml"]) == 2
assert sum(int(x["tests"]) for x in report["xml"]) == 2
assert sum(int(x["failures"]) for x in report["xml"]) == 1
assert sum(int(x["errors"]) + int(x["skipped"]) for x in report["xml"]) == 0
assert (root / "run.exit").read_text().strip() == "1"
report["verdict"] = "BACKEND_PIPELINING_PASS__WHOLE_CORE_THROUGHPUT_FAIL"
report["hashes"] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in root.iterdir() if p.is_file() and p.name != "analysis.json"}
(root / "analysis.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps({"verdict": report["verdict"], "whole_core": report["whole_core"]}, indent=2))
