li x1,1
li x2,2
li x3,3
li x4,4
li x5,0xff
bne x1,x2,jump
j error
addi x30,x0,1
addi x2,x1,1
beq x1,x2,bru
j error
error:
    li x5,0xf
    j end
jump:
    li x5,0
    j end
bru:
    li x5,1
    j end
end:
    csrrw x0,0x8ff,x5
loop:
    addi x31,x0,1
    j loop
