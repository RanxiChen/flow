FLOW_ROOT ?= ../../..

clean:
	rm -rf $(FLOW_ROOT)/design/build obj_dir *.vcd

sram_v:
	rm -rf $(FLOW_ROOT)/design/build
	cd $(FLOW_ROOT)/design && sbt "runMain mem.GenerateSRAMLayer"
	sed -E  -i 's/mem\.hex/mem01\.hex/g' $(FLOW_ROOT)/design/build/*.sv
	verilator --cc -f $(FLOW_ROOT)/design/build/filelist.f \
	--top-module SRAMLayer -I$(FLOW_ROOT)/design/build

sram_sim:
	sed -E  -i 's/mem\.hex/mem01\.hex/g' $(FLOW_ROOT)/design/build/*.sv
	verilator --cc -f $(FLOW_ROOT)/design/build/filelist.f \
	--top-module SRAMLayer -I$(FLOW_ROOT)/design/build -DENABLE_INITIAL_MEM_ \
	--exe sramlayer.cpp --trace
	make -C obj_dir -f VSRAMLayer.mk VSRAMLayer
	./obj_dir/VSRAMLayer

sram_mem_v:
	rm -rf $(FLOW_ROOT)/design/build
	cd $(FLOW_ROOT)/design && sbt "runMain mem.GenerateSRAMMemory"
	sed -E  -i 's/mem\.hex/mem01\.hex/g' $(FLOW_ROOT)/design/build/*.sv
	#verilator --cc -f $(FLOW_ROOT)/design/build/filelist.f \
	#--top-module SRAMMemory -I$(FLOW_ROOT)/design/build

sram_mem_sim:
	sed -E  -i 's/mem\.hex/mem01\.hex/g' $(FLOW_ROOT)/design/build/*.sv
	verilator --cc -f $(FLOW_ROOT)/design/build/filelist.f \
	--top-module SRAMMemory -I$(FLOW_ROOT)/design/build -DENABLE_INITIAL_MEM_ \
	--exe srammemory.cpp --trace
	make -C obj_dir -f VSRAMMemory.mk VSRAMMemory
	./obj_dir/VSRAMMemory