FLOW_ROOT ?= ../../
DESIGN_PRJ := $(FLOW_ROOT)/design
V_FILES_DIR := $(DESIGN_PRJ)/build

verilog:$(DESIGN_PRJ)/src/main/scala/core/core.scala
	rm -rf $(DESIGN_PRJ)/build
	cd $(DESIGN_PRJ) && sbt "runMain core.GenerateCoreVerilogFile"

verilator_files:$(V_FILES_DIR)/filelist.f
	verilator --cc -f $< --top-module core_in_order -I$(V_FILES_DIR)

build:main.cpp
	verilator --trace \
	--cc -f $(V_FILES_DIR)/filelist.f --top-module core_in_order  \
	--exe main.cpp \
	-CFLAGS  "-I$(FLOW_ROOT)/csrc -I$(FLOW_ROOT)/include -Iobj_dir" \
	-I$(V_FILES_DIR)
exe:build
	make -C obj_dir -f Vcore_in_order.mk Vcore_in_order

run:exe
	./obj_dir/Vcore_in_order

clean:
	rm -rf $(V_FILES_DIR) obj_dir 
	rm -rf *.vcd

hello:hello.cpp
	verilator --cc $(V_FILES_DIR)/helloModule.sv --exe  hello.cpp --trace
	make -C obj_dir -f VhelloModule.mk
	./obj_dir/VhelloModule