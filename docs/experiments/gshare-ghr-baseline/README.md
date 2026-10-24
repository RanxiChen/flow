# GShare/GHR prediction timing baseline (2026-10-24)

This isolated experiment measures the current three-query GShare and speculative-history feedback at 200 MHz. It does not change production RTL.

## Source and boundaries

- Production `BreezeBankedPht`: default historyBits=10, phtBanks=4, 1024 two-bit counters, three logical queries with one read per bank; unchanged and instantiated directly.
- Index equations copied from `BreezePipelinePredictor`; F1 direction/history equations and pending-history bypass copied from `BreezeFrontendPipeline.preview` and request history selection.
- F0 input fixture registers -> hash/bank arbitration/initialized lookup -> synchronous PHT read capture.
- F1 PHT result -> slot direction choice -> conditional history append -> forecastHistory.
- F1 earlyHistory -> same-cycle next F0 history mux/hash/bank arbitration -> next PHT read capture. No register is inserted to cut this path.
- BTB hit/kind and page/access-fault/mask metadata are supplied at the query boundary and captured beside the PHT read. The actual BTB read/tag/reorder delay is excluded; this is not a combined BTB+PHT or full frontend closure result.
- Query validity models accepted queries, including idle gaps. Cancellation, cache admission, refill replay, F2 repair/real instruction decode, target/next-PC recurrence and RAS are outside this extracted scope. There are no cancellation inputs creating spurious combinational dependencies.
- Original training circuitry remains writable to prevent constant-folding the PHT. Runtime training QoS and training-path closure are not the requested prediction acceptance criterion.

## Fixture and validation

Input/output fixture registers use one BUFGCE clock; the core feedback has no added register. Fixture Valid training offers are sampled unconditionally, with a registered actual enqueue acknowledgement; they are not a Decoupled producer contract. For a query sampled at fixture edge N, observable fixture result is captured at N+2.

The integer oracle initializes all 1024 cells through settled production updates, then checks 3000 random accepted/idle cycles with continuous history feedback, Q1/Q2 selection, per-bank arbitration/broadcast, slot priority, conditional/nonconditional branches, access/page faults, second-slot masks, and PC+2 wrap. No training is concurrent with this prediction-focused comparison.

## Physical constraints

Alan; Vivado 2022.2; xcku040-ffva1156-2-e; 5.000 ns clock; 0.100 ns clock uncertainty. OOC fixture I/O max/min delays are 0.500/0.500 ns, referenced to boundary-register clock pins. Physical partition anchors model local synchronous neighbors. No false/multicycle paths. Flow: OOC synthesis, opt, place, AggressiveExplore phys-opt, NoTimingRelaxation route. These are module-level routed results, not integrated/frontend/board proof.

## Routed result

Prediction setup **passes 200 MHz** in this extraction. No extra stage is justified by this measurement. Original F0/F1 staging can be retained for this isolated portion. The combined BTB + direction/history + PC/cache-control path remains unmeasured.

| Path group | Worst data path | Setup slack at 5 ns |
|---|---:|---:|
| F0 registered input -> query capture | 3.584 ns | +1.284 ns |
| F1 PHT read -> forecastHistory | 3.335 ns | +1.533 ns |
| F1 history feedback -> next F0 initialized-flag capture | 4.613 ns | +0.212 ns |
| F1 feedback -> next PHT SRAM read-address capture | see audit report | +0.629 ns |
| F1 feedback -> next query bookkeeping | see audit report | +0.274 ns |

Slack includes actual setup/clock skew/uncertainty; it is not simply 5 minus data delay. The tighter path has 13 logic levels, 1.381 ns logic and 3.232 ns routing (70.06%). It starts at `core/pht/ram_0_ext/_R0_addr_d0_reg[2]/C` and ends at `core/pht/init_2_reg/D`: read PHT -> direction/history -> next hash -> select the next query's initialized flag. The nominal F1-only forecastHistory path has substantially more margin.

The audited prediction endpoint union (forecast/history/metadata/pending state, PHT read-address, index/grant/bypass/initialized snapshots) has WNS +0.212 ns and WHS +0.048 ns. All top-level setup paths pass. The complete fixture still has one **training queue hold violation**, WHS -0.016 ns, THS -0.016 ns: `core/pht/updates/ram_ext/.../RAMA/CLK` -> `core/pht/training_taken_reg/D`, data 0.171 ns. Thus this is prediction timing success, not complete module timing closure. Increasing pipeline depth does not cure this short-path hold issue.

No setup failing endpoints, no unconstrained endpoints, no clock/IO-delay/loop check omissions, no timing exceptions, 2636/2636 routable nets fully routed, zero routing errors. Pulse-width slack +1.832 ns. Resources: 2692 LUT (2620 logic + 72 LUTRAM), 1307 FF including 97 fixture FF; BRAM/DSP 0. These resource totals include the preserved training circuitry.

Functional oracle: 1/1 tests passed; 2769 accepted predictions, 1377 denied logical queries due to per-bank conflict, 42 exercised Q2 selections. All 1024 counters preloaded via original training updates; 3000 subsequent query/idle cycles. This does not establish integrated frontend behavior.

## Evidence and stage decision

- Hardware + tests: `f4af4678` on `experiment/gshare-ghr-baseline-20261024`; source is unchanged by the audit-only `cd90ceb8` commit.
- Alan root: `/home/chen/FUN/flow-runs/gshare-ghr-baseline-20261024`; `attempt-01` functional/RTL exit 0; `route-r1` Vivado exit 0; `audit-r1` checkpoint audit exit 0. Tool completion is distinct from the reported training hold violation.
- Matched scripts, generated SV, tests XML, routed reports and hashes are under `records/gshare-ghr-baseline-20261024/`.
- Routed checkpoint SHA256: `adf4bb5f6fca16e0e00657ecd7c74c40849d5d4961ce403ba3062859b6b4b9c5` (kept on Alan).

**Stage estimate: add 0 stages for standalone GShare/GHR.** Retain the F0 read/F1 decision baseline while measuring the actual BTB-result arrival plus direction/history feedback next. +0.212 ns is a narrow margin and does not guarantee success after integration or another placement. If a later combined run needs another register, its position must respect speculative-history recurrence; delaying earlyHistory changes which history a following query receives, and requires an explicit admission/stall or prediction contract. No split RTL was implemented in this baseline.
