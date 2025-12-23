li x5,0
li x6,5
loop:
	addi x5,x5,1
	bne x5,x6,loop
default:
	csrrw x0,0x8ff,x5
	addi x5,x5,1
	j default

