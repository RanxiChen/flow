run:sim_datapath
	./sim_datapath.elf

sim_datapath:
	g++ -o sim_datapath.elf main.cpp -I../../csrc \
	 -I../../include 

clean:
	rm -f sim_datapath.elf