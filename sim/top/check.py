#!/usr/bin/python3
import re
print("read which cycle core finish one execution\n")
CycleInstMap ={}
# read which cycle instruction retires
with open("cycle_inst.raw","r") as f:
    for line in f:
        content = line.split(',')
        cycle = int(content[0].strip())+ 1
        inst_count = int(content[1].strip())
        CycleInstMap[inst_count] = cycle

print("get inst and cycle map\n")
print("check result by scan log file\n")
print("=====================================\n")
RegFile = [0]*32
read_rf = False
read_status = False
do_check = False
cycle = 0
inst = 1
pattern = r'x\[([0-9]{2})\]=(0x[0-9a-fA-F]{16})'

def check_execute():
    # read global cycle and regfile content
    global cycle
    global inst
    if(cycle == CycleInstMap.get(inst, -1)):
        # do check
        match(inst):
            case 1:
                assert (RegFile[15] ==1), f"[FAIL] inst {inst} retires at cycle {cycle}, but x15={RegFile[15]}, expected 1"
            case 2:
                assert (RegFile[16] ==7), f"[FAIL] inst {inst} retires at cycle {cycle}, but x16={RegFile[16]}, expected 6"
            case 3:
                assert (RegFile[17] ==1), f"[FAIL] inst {inst} retires at cycle {cycle}, but x17={RegFile[17]}, expected 15"
            case 4:
                assert (RegFile[8] == RegFile[16] - RegFile[15])
            case 5:
                assert (RegFile[5] == 0^ 258), f"[FAIL] inst {inst} retires at cycle {cycle}, but x5={RegFile[5]}, expected {0 ^ 258}"
            case 6:
                assert (RegFile[6] == 1), f"[FAIL] inst {inst} retires at cycle {cycle}, but x6={RegFile[6]}, expected 1"
            case 7:
                assert (RegFile[7] == 0), f"[FAIL] inst {inst} retires at cycle {cycle}, but x7={RegFile[7]}, expected 0"
            case 8:
                assert (RegFile[8] == RegFile[5] ), f"[FAIL] inst {inst} retires at cycle {cycle}, but x8={RegFile[8]}, expected x5={RegFile[5]}"
            case 9:
                assert (RegFile[1] == int("0x00100793",16)), f"[FAIL] inst {inst} retires at cycle {cycle}, but x1={RegFile[1]}, expected {bin('0x00100793',16)}"
            case 10:
                assert (RegFile[2] == int("0x00678813",16)), f"[FAIL] inst {inst} retires at cycle {cycle}, but x2={RegFile[2]}, expected {bin('0x00678813',16)}"
            case 12:
                assert (RegFile[1] == int("0x00000000",16)), f"[FAIL] inst {inst} retires at cycle {cycle}, but x1={RegFile[1]}, expected {bin('0x00000000',16)}"
            case 19:
                assert (RegFile[4] == int("0x0000000040000100",16)), f"[FAIL] inst {inst} retires at cycle {cycle}, but x4={RegFile[4]}, expected {bin('0x0000000040000100',16)}"
            case _:
                pass
        print(f"[PASS] inst {inst} retires at cycle {cycle} as expected")
        inst += 1




with open("flow_top.log","r") as f:
    for line in f:
        if("RegFile" in line):
            # next line will get regfile content
            read_rf = True
            read_status = False
            do_check =  False
            continue
        elif("Stats" in line):
            read_rf = False
            read_status = True
            do_check =  False
            pattern2 = r'\d+'
            match = re.search(pattern2,line)
            if(match):
                cycle = int(match.group())
            continue
        elif("Pipe" in line):
            read_rf = False
            read_status = False
            do_check =  True
            continue
        elif("***" in line):
            # end of a section
            read_rf = False
            read_status = False
            # do check
            check_execute()
            continue
        else:
            # process content
            if(read_rf and len(line) > 2 ):
                #print(line,len(line))
                content = line.split(' ')
                if(len(content) > 1):
                    for item in content:
                        match = re.search(pattern,item)
                        if(match):
                            reg_id = int(match.group(1))
                            reg_val = int(match.group(2),16)
                            RegFile[reg_id] = reg_val