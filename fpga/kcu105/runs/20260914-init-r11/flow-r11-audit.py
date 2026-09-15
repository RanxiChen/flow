import json,pathlib,re,hashlib
p=pathlib.Path('/home/chen/FUN/flow/build/fpga/kcu105-single-init-ila-100mhz-20260914-r11')
csr=json.loads((p/'csr.json').read_text()); probes=json.loads((p/'ila-probes.json').read_text())
assert csr['memories']['main_ram']['base']==0x80000000
assert csr['memories']['main_ram']['size']==0x80000000
assert not any('sdcard' in k or 'spiflash' in k for k in csr['csr_bases'])
assert not any('sdcard' in k or 'spiflash' in k for k in csr['memories'])
assert probes['clock_hz']==100000000 and probes['storage_qualifier']==False
assert not any(x['signal'].startswith(('dbg_sd_','dbg_dma_')) for x in probes['probes'])
manifest=json.loads((p/'source-snapshot/manifest.json').read_text())
for x in manifest: assert hashlib.sha256(pathlib.Path(x['snapshot']).read_bytes()).hexdigest()==x['sha256']
pma=[x for x in manifest if pathlib.Path(x['snapshot']).name=='PMAChecker.sv']
assert len(pma)==1
rtl=pathlib.Path(pma[0]['snapshot']).read_text()
assert 'physicalAddressValid & io_query_addr[31] & _lastAddressExtended_T_2[31]' in rtl
print('R11_INPUT_AUDIT_PASS: 2GiB SoC/PMA, CPU ILA, no SD/Flash CSR or DMA probes, frozen RTL hashes match')
