// R02 reference extension. ISA source pinned to riscv/riscv-dot-product
// 813cba14c9f0a731b4904925851a2820a6320b5b. The published Sail listing
// contains an undefined `product`; implement the normative prose and test it
// independently with signed-byte corner cases, masks and modulo-2^32 overflow.
#include "extension.h"
#include "insn_macros.h"
#include "decode_macros.h"
#include "mmu.h"
#include <cstdint>
#include <cstdio>

static reg_t dot(processor_t* p, insn_t insn, reg_t pc) {
  require_vector(true);
  require(p->VU.vsew == 32);
  const bool unsigned_scalar = ((insn.bits() >> 26) & 63) == 42;
  const uint32_t scalar = uint32_t(p->get_state()->XPR[insn.rs1()]);
  for (reg_t i = p->VU.vstart->read(); i < p->VU.vl->read(); ++i) {
    if (!insn.v_vm() && !p->VU.mask_elt(0,i)) continue;
    const uint32_t source = p->VU.elt<uint32_t>(insn.rs2(),i);
    uint32_t value = p->VU.elt<uint32_t>(insn.rd(),i);
    for (unsigned j=0;j<4;++j) {
      const int32_t a = int8_t(source >> (8*j));
      const int32_t b = unsigned_scalar ? int32_t(uint8_t(scalar >> (8*j))) : int32_t(int8_t(scalar >> (8*j)));
      value += uint32_t(a*b);
    }
    p->VU.elt<uint32_t>(insn.rd(),i,true) = value;
  }
  p->VU.vstart->write(0);
  return pc+4;
}

// A reference-only checkpoint executes immediately before each tested V
// instruction. It observes actual architectural CSR and scalar operand values;
// the DUT driver consumes these records, never its own arithmetic model.
static reg_t snapshot(processor_t* p, insn_t insn, reg_t pc) {
  if (insn.funct3() == 0) {
    const uint32_t word = p->get_mmu()->load<uint32_t>(pc+4);
    insn_t next(word);
    std::fprintf(stderr,"R02_TRACE {\"instruction\":%u,\"rs1\":%llu,\"rs2\":%llu,\"vl\":%llu,\"vtype\":%llu,\"vstart\":%llu,\"rd\":%u,\"regs\":[",
      word,(unsigned long long)p->get_state()->XPR[next.rs1()],
      (unsigned long long)p->get_state()->XPR[next.rs2()],
      (unsigned long long)p->VU.vl->read(),(unsigned long long)p->VU.vtype->read(),
      (unsigned long long)p->VU.vstart->read(),unsigned(next.rd()));
    const auto* bytes = static_cast<const uint8_t*>(p->VU.reg_file);
    for (unsigned r=0;r<32;++r) {
      std::fprintf(stderr,"%s\"",r ? ",":"");
      for(unsigned b=0;b<p->VU.vlenb;++b) std::fprintf(stderr,"%02x",bytes[r*p->VU.vlenb+b]);
      std::fprintf(stderr,"\"");
    }
    std::fprintf(stderr,"],\"xpr\":[");
    for(unsigned r=0;r<32;++r) std::fprintf(stderr,"%s%llu",r ? ",":"",(unsigned long long)p->get_state()->XPR[r]);
    std::fprintf(stderr,"]}\n");
  } else {
    const reg_t base = p->get_state()->XPR[insn.rs1()];
    const reg_t length = p->get_state()->XPR[insn.rs2()];
    std::fprintf(stderr,"R02_MEMORY %llu ",(unsigned long long)base);
    for(reg_t b=0;b<length;++b) std::fprintf(stderr,"%02x",p->get_mmu()->load<uint8_t>(base+b));
    std::fprintf(stderr,"\n");
  }
  return pc+4;
}

class r02_dot_t : public extension_t {
 public:
  const char* name() const override { return "r02_dot"; }
  std::vector<insn_desc_t> get_instructions(const processor_t&) override {
    return {
      {0xb0006057,0xfc00707f,dot,dot,dot,dot,dot,dot,dot,dot},
      {0xa8006057,0xfc00707f,dot,dot,dot,dot,dot,dot,dot,dot},
      {0x0000000b,0x0000707f,snapshot,snapshot,snapshot,snapshot,snapshot,snapshot,snapshot,snapshot},
      {0x0000100b,0x0000707f,snapshot,snapshot,snapshot,snapshot,snapshot,snapshot,snapshot,snapshot}
    };
  }
  std::vector<disasm_insn_t*> get_disasms(const processor_t*) override { return {}; }
};
REGISTER_EXTENSION(r02_dot,[](){return new r02_dot_t;})
