%.o:%.S
	riscv64-unknown-elf-as -o $@ $<

%.raw:%.o
	riscv64-unknown-elf-objdump -d $< | tee $@

%.hex:%.o
	riscv64-unknown-elf-objdump -d $< | \
	sed -En 's/^[[:space:]]*[0-9a-f]+:[[:space:]]+([0-9a-f]{8}).*/\1/p' | \
	awk -f o2hex.awk > $@

all: %.hex
clean:
	rm -rf *.o *.raw *.hex