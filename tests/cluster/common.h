// Shared runtime for cluster self-check programs (docs/tasks/CLUSTER-sim-abi.md).
//
// Result ABI: hart h stores its result to tohost + 64*h from M mode (the
// harness matches the committed store address, which is virtual, so it is
// only ever written untranslated). 1 = pass, (code << 1) | 1 = fail.
//
// Every mode finishes the same way: a0 = 0 (pass) or failure code, a7 =
// EXIT_MAGIC, ecall. The M-mode trap handler turns an unexpected ecall
// carrying EXIT_MAGIC into the tohost store.
//
// Register conventions: s11 = mhartid (set by TEST_INIT, never changed);
// t0-t5 are preserved by both trap handlers, t6 is swapped through
// mscratch/sscratch. Programs must not leave a7 = EXIT_MAGIC around ecalls
// they expect to trap normally.
//
// Per-hart trap area (HART_AREA bytes, M view at +0, S view at +S_AREA):
//   EXPECT  cause the next trap must have (-1: none expected)
//   CAUSE/EPC/TVAL  recorded on an expected trap
//   COUNT   expected traps taken
//   RESUME  0: return to epc+4; else return there (S handler forces SPP=S)
//   HOOK    interrupt hook (M only); jumped to with t6 = area, t0-t5 saved,
//           must end with `j m_ret`
//   PRIV    MPP/SPP of the trapped context (recorded on an expected trap)

#define EXIT_MAGIC 0x5e7
#define TOHOST_STRIDE 64
#define HART_AREA 256
#define S_AREA 128
#define A_EXPECT 0
#define A_CAUSE 8
#define A_EPC 16
#define A_TVAL 24
#define A_COUNT 32
#define A_RESUME 40
#define A_HOOK 48
#define A_PRIV 56
#define A_SAVE 64

#define MAX_HARTS 4

// Platform (config/breeze_mcu_platform.json)
#define CLINT_BASE    0x02000000
#define CLINT_MTIMECMP 0x02004000
#define CLINT_MTIME   0x0200bff8
#ifdef QEMU
// Local logic check on qemu-system-riscv64 -M virt (same CLINT layout; the
// console is the virt UART; results also go to the sifive_test finisher).
#define CONSOLE       0x10000000
#define QEMU_FINISHER 0x100000
#else
#define CONSOLE       0x12000000
#endif
#define GPIO0         0x12002000
#define HOLE          0x40000000

#define MSTATUS_SIE  (1 << 1)
#define MSTATUS_MIE  (1 << 3)
#define MSTATUS_SPP  (1 << 8)
#define MSTATUS_MPP  (3 << 11)
#define MSTATUS_MPP_S (1 << 11)
#define MSTATUS_SUM  (1 << 18)
#define MSTATUS_MXR  (1 << 19)
#define MIP_MSIP (1 << 3)
#define MIP_MTIP (1 << 7)

#define CAUSE_FETCH_ACCESS 1
#define CAUSE_ILLEGAL 2
#define CAUSE_LOAD_MISALIGNED 4
#define CAUSE_LOAD_ACCESS 5
#define CAUSE_STORE_MISALIGNED 6
#define CAUSE_STORE_ACCESS 7
#define CAUSE_ECALL_U 8
#define CAUSE_ECALL_S 9
#define CAUSE_ECALL_M 11
#define CAUSE_FETCH_PAGE 12
#define CAUSE_LOAD_PAGE 13
#define CAUSE_STORE_PAGE 15

// Sv39 PTE bits
#define PTE_V 0x01
#define PTE_R 0x02
#define PTE_W 0x04
#define PTE_X 0x08
#define PTE_U 0x10
#define PTE_G 0x20
#define PTE_A 0x40
#define PTE_D 0x80
#define SATP_SV39 (8 << 60)

// ---------------------------------------------------------------------------
// Program entry: TEST_INIT at _start, in M mode.
// ---------------------------------------------------------------------------
.macro TEST_INIT
  la t0, m_trap
  csrw mtvec, t0
  csrr s11, mhartid
  la t0, hart_area
  slli t1, s11, 8
  add t0, t0, t1
  csrw mscratch, t0
  addi t1, t0, S_AREA
  csrw sscratch, t1
  li t1, -1
  sd t1, A_EXPECT(t0)
  sd t1, S_AREA + A_EXPECT(t0)
  sd zero, A_COUNT(t0)
  sd zero, S_AREA + A_COUNT(t0)
  sd zero, A_RESUME(t0)
  sd zero, S_AREA + A_RESUME(t0)
  sd zero, A_HOOK(t0)
  // PMP: S/U may access everything (no matching entry would deny them).
  li t1, -1
  csrw pmpaddr0, t1
  li t1, 0x1f
  csrw pmpcfg0, t1
  csrw mie, zero
  csrw medeleg, zero
  csrw mideleg, zero
  csrw satp, zero
  la t1, s_trap
  csrw stvec, t1
.endm

// Harts at or above \n park forever (no interrupt enabled, so WFI never wakes).
.macro PARK_IF_HART_GE n
  li t0, \n
  bgeu s11, t0, park
.endm

// reg <- this hart's trap area (clobbers t5) (works in M, and in S with RAM identity mapped).
.macro AREA reg
  la \reg, hart_area
  slli t5, s11, 8
  add \reg, \reg, t5
.endm

.macro EXPECT cause
  AREA t4
  li t5, \cause
  sd t5, A_EXPECT(t4)
.endm

.macro EXPECT_RESUME cause, label
  AREA t4
  la t5, \label
  sd t5, A_RESUME(t4)
  li t5, \cause
  sd t5, A_EXPECT(t4)
.endm

.macro S_EXPECT cause
  AREA t4
  li t5, \cause
  sd t5, S_AREA + A_EXPECT(t4)
.endm

.macro S_EXPECT_RESUME cause, label
  AREA t4
  la t5, \label
  sd t5, S_AREA + A_RESUME(t4)
  li t5, \cause
  sd t5, S_AREA + A_EXPECT(t4)
.endm

// reg <- field of this hart's M (off) or S (S_AREA + off) record.
.macro SEEN reg, off
  AREA t4
  ld \reg, \off(t4)
.endm

.macro PASS
  li a0, 0
  li a7, EXIT_MAGIC
  ecall
.endm

.macro FAIL code
  li a0, \code
  li a7, EXIT_MAGIC
  ecall
.endm

.macro CHECK_EQ ra, rb, code
  beq \ra, \rb, .Lok\@
  FAIL \code
.Lok\@:
.endm

.macro CHECK_NE ra, rb, code
  bne \ra, \rb, .Lok\@
  FAIL \code
.Lok\@:
.endm

// Uses t4 as scratch: \ra must not be t4.
.macro CHECK_IMM ra, imm, code
  li t4, \imm
  CHECK_EQ \ra, t4, \code
.endm

// The previous instruction must have taken the expected trap with \cause.
.macro CHECK_TRAP_M cause, code
  SEEN t5, A_EXPECT
  li t4, -1
  CHECK_EQ t5, t4, \code
  SEEN t5, A_CAUSE
  CHECK_IMM t5, \cause, \code
.endm

.macro CHECK_TRAP_S cause, code
  SEEN t5, S_AREA + A_EXPECT
  li t4, -1
  CHECK_EQ t5, t4, \code
  SEEN t5, S_AREA + A_CAUSE
  CHECK_IMM t5, \cause, \code
.endm

// mret into S at \label with MPIE preserved.
.macro ENTER_S label
  li t0, MSTATUS_MPP
  csrc mstatus, t0
  li t0, MSTATUS_MPP_S
  csrs mstatus, t0
  la t0, \label
  csrw mepc, t0
  mret
.endm

// dst <- leaf/pointer PTE for physical address in \pa (register), flags \fl.
.macro MK_PTE dst, pa, fl
  srli \dst, \pa, 12
  slli \dst, \dst, 10
  ori \dst, \dst, \fl
.endm

// Spin until the dword at \addr_reg >= \val_reg (bounded; FAIL \code).
// Clobbers t3.
.macro WAIT_GE addr_reg, val_reg, code
  li t3, 1000000
.Lw\@:
  ld t5, 0(\addr_reg)
  bge t5, \val_reg, .Ld\@
  addi t3, t3, -1
  bnez t3, .Lw\@
  FAIL \code
.Ld\@:
.endm

// One-shot barrier on dword \var across NHARTS harts. Clobbers t0-t3, t5.
.macro BARRIER var, code
  la t0, \var
  li t1, 1
  amoadd.d.aqrl x0, t1, (t0)
  li t2, NHARTS
  WAIT_GE t0, t2, \code
  fence rw, rw
.endm

// ---------------------------------------------------------------------------
// Runtime code: M and S trap handlers, finish, park. Include once.
// ---------------------------------------------------------------------------
.macro TEST_RUNTIME
  .text
  .balign 64
m_trap:
  csrrw t6, mscratch, t6
  sd t0, A_SAVE + 0(t6)
  sd t1, A_SAVE + 8(t6)
  sd t2, A_SAVE + 16(t6)
  sd t3, A_SAVE + 24(t6)
  sd t4, A_SAVE + 32(t6)
  sd t5, A_SAVE + 40(t6)
  csrr t0, mcause
  bltz t0, m_irq
  ld t1, A_EXPECT(t6)
  bne t0, t1, m_unexpected
  sd t0, A_CAUSE(t6)
  csrr t1, mepc
  sd t1, A_EPC(t6)
  csrr t2, mtval
  sd t2, A_TVAL(t6)
  csrr t2, mstatus
  srli t2, t2, 11
  andi t2, t2, 3
  sd t2, A_PRIV(t6)
  ld t3, A_COUNT(t6)
  addi t3, t3, 1
  sd t3, A_COUNT(t6)
  li t3, -1
  sd t3, A_EXPECT(t6)
  ld t3, A_RESUME(t6)
  sd zero, A_RESUME(t6)
  bnez t3, 1f
  addi t3, t1, 4
1:
  csrw mepc, t3
m_ret:
  ld t0, A_SAVE + 0(t6)
  ld t1, A_SAVE + 8(t6)
  ld t2, A_SAVE + 16(t6)
  ld t3, A_SAVE + 24(t6)
  ld t4, A_SAVE + 32(t6)
  ld t5, A_SAVE + 40(t6)
  csrrw t6, mscratch, t6
  mret
m_unexpected:
  li t1, EXIT_MAGIC
  bne a7, t1, 2f
  li t1, CAUSE_ECALL_U
  beq t0, t1, finish_m
  li t1, CAUSE_ECALL_S
  beq t0, t1, finish_m
  li t1, CAUSE_ECALL_M
  beq t0, t1, finish_m
2:
  // Unexpected synchronous trap: fail with 0x200 + cause.
  addi a0, t0, 0x200
  j finish_m
m_irq:
  ld t1, A_HOOK(t6)
  beqz t1, 3f
  jr t1
3:
  // Unexpected interrupt: fail with 0x280 + cause.
  andi a0, t0, 0x3f
  addi a0, a0, 0x280
  j finish_m

finish_m:
  csrr t0, mhartid
  slli t0, t0, 6
  la t1, tohost
  add t1, t1, t0
  beqz a0, 4f
  slli a0, a0, 1
  ori a0, a0, 1
  j 5f
4:
  li a0, 1
5:
  fence rw, rw
  sd a0, 0(t1)
  fence rw, rw
#ifdef QEMU
  li t1, 1
  bne a0, t1, 8f
  la t0, qemu_done
  amoadd.d t2, t1, (t0)
  addi t2, t2, 1
  li t1, NHARTS
  blt t2, t1, park
  li t0, QEMU_FINISHER
  li t1, 0x5555
  sw t1, 0(t0)
  j park
8:
  li t0, QEMU_FINISHER
  slli t1, a0, 16
  li t2, 0x3333
  or t1, t1, t2
  sw t1, 0(t0)
#endif
park:
  csrw mie, zero
  li t0, MSTATUS_MIE
  csrc mstatus, t0
6:
  wfi
  j 6b

  .balign 64
s_trap:
  csrrw t6, sscratch, t6
  sd t0, A_SAVE + 0(t6)
  sd t1, A_SAVE + 8(t6)
  sd t2, A_SAVE + 16(t6)
  sd t3, A_SAVE + 24(t6)
  sd t4, A_SAVE + 32(t6)
  sd t5, A_SAVE + 40(t6)
  csrr t0, scause
  ld t1, A_EXPECT(t6)
  bne t0, t1, s_unexpected
  sd t0, A_CAUSE(t6)
  csrr t1, sepc
  sd t1, A_EPC(t6)
  csrr t2, stval
  sd t2, A_TVAL(t6)
  csrr t2, sstatus
  srli t2, t2, 8
  andi t2, t2, 1
  sd t2, A_PRIV(t6)
  ld t3, A_COUNT(t6)
  addi t3, t3, 1
  sd t3, A_COUNT(t6)
  li t3, -1
  sd t3, A_EXPECT(t6)
  ld t3, A_RESUME(t6)
  sd zero, A_RESUME(t6)
  bnez t3, 1f
  addi t3, t1, 4
  j 2f
1:
  // Resume targets are S code: return to S even when U trapped.
  li t2, MSTATUS_SPP
  csrs sstatus, t2
2:
  csrw sepc, t3
  ld t0, A_SAVE + 0(t6)
  ld t1, A_SAVE + 8(t6)
  ld t2, A_SAVE + 16(t6)
  ld t3, A_SAVE + 24(t6)
  ld t4, A_SAVE + 32(t6)
  ld t5, A_SAVE + 40(t6)
  csrrw t6, sscratch, t6
  sret
s_unexpected:
  // Unexpected delegated trap: fail with 0x300 + cause (exits through M).
  addi a0, t0, 0x300
  li a7, EXIT_MAGIC
  ecall
7:
  j 7b

  .section .tohost, "aw", @progbits
  .balign 64
  .globl tohost
tohost:
  .zero TOHOST_STRIDE * MAX_HARTS

  .data
  .balign 64
hart_area:
  .zero HART_AREA * MAX_HARTS
#ifdef QEMU
  .balign 64
qemu_done:
  .dword 0
#endif
  .text
.endm
