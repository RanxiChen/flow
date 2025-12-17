FLOW_ROOT ?= ../../..
run:sim_datapath
	./sim_datapath.elf

sim_datapath:
	g++ -o sim_datapath.elf main.cpp -I../../csrc \
	 -I../../include 

clean:
	rm -f *.elf
	rm -rf obj_dir/

sim_tinymem_v:
	rm -rf $(FLOW_ROOT)/design/build
	cd $(FLOW_ROOT)/design && sbt 'runMain hello.GenerateHellMemFile'
	verilator --cc -f $(FLOW_ROOT)/design/build/filelist.f \
	--top-module tinymem -I$(FLOW_ROOT)/design/build

sim_tinymem:
	rm -rf $(FLOW_ROOT)/design/build
	cd $(FLOW_ROOT)/design && sbt 'runMain hello.GenerateHellMemFile'
	verilator --cc -f $(FLOW_ROOT)/design/build/filelist.f \
	--top-module tinymem -I$(FLOW_ROOT)/design/build \
	-I./misc/ --exe sim_hellomem.cpp --trace -DENABLE_INITIAL_MEM_
	make -C obj_dir -f Vtinymem.mk Vtinymem
	./obj_dir/Vtinymem