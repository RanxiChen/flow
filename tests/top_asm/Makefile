%.o:%.S
	riscv64-unknown-elf-as -o $@ $<

%.raw:%.o
	riscv64-unknown-elf-objdump -d $< | tee $@

%.itcm.hex:%.o
	riscv64-unknown-elf-objdump -d $< | \
	sed -En 's/^[[:space:]]*[0-9a-f]+:[[:space:]]+([0-9a-f]{8}).*/\1/p' > $@

%.hex:%.o
	riscv64-unknown-elf-objdump -d $< | \
	sed -En 's/^[[:space:]]*[0-9a-f]+:[[:space:]]+([0-9a-f]{8}).*/\1/p' | \
	awk -f o2hex.awk > $@

all: test02.itcm.hex test03.itcm.hex test04.itcm.hex
clean:
	rm -rf *.o *.raw *.hex