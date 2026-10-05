// Single-vCPU R01 profiler for QEMU 11's public TCG plugin API.
// Scalar instruction counts use inline scoreboards; only vector instructions
// and quantized-kernel entries invoke register-reading callbacks.
#include <qemu-plugin.h>
#include <glib.h>
#include <inttypes.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

QEMU_PLUGIN_EXPORT int qemu_plugin_version = QEMU_PLUGIN_VERSION;
typedef struct { uint64_t scalar, vector, vset; } Counts;
typedef struct {
    uint64_t start, size;
    char *name;
    Counts snapshots[3];
    uint64_t calls[2], blocks[2], loads[2][4], stores[2][4];
} Function;
typedef struct { uint64_t count; } Frequency;
typedef struct {
    unsigned fn;
    char *op;
    int memory, is_vset;
    unsigned last_phase;
    uint64_t last_vt, last_vl;
    Frequency *last_frequency;
} Instruction;
static GArray *functions;
static GHashTable *frequencies;
static struct qemu_plugin_scoreboard *score;
static struct qemu_plugin_register *vl_reg, *type_reg, *n_reg, *nc_reg;
static GByteArray *regbuf;
static unsigned phase;
static char *output;

static uint64_t read_reg(struct qemu_plugin_register *reg) {
    if (!reg || !qemu_plugin_read_register(reg, regbuf) || regbuf->len != 8) {
        fprintf(stderr, "R01 register read failed\n");
        abort();
    }
    uint64_t value;
    memcpy(&value, regbuf->data, sizeof(value));
    return GUINT64_FROM_LE(value);
}
static Function *fn(unsigned i) { return &g_array_index(functions, Function, i); }
static unsigned find_function(uint64_t pc) {
    unsigned lo = 1, hi = functions->len;
    while (lo < hi) {
        unsigned mid = (lo + hi) / 2;
        if (fn(mid)->start <= pc) lo = mid + 1;
        else hi = mid;
    }
    if (lo > 1 && pc < fn(lo - 1)->start + fn(lo - 1)->size) return lo - 1;
    return 0;
}
static void init(qemu_plugin_id_t id, unsigned cpu) {
    (void) id;
    if (cpu != 0) {
        fprintf(stderr, "R01 expects one guest vCPU; refusing ambiguous multithread counts\n");
        abort();
    }
    GArray *regs = qemu_plugin_get_registers();
    for (guint i = 0; i < regs->len; ++i) {
        qemu_plugin_reg_descriptor *r = &g_array_index(regs, qemu_plugin_reg_descriptor, i);
        if (!strcmp(r->name, "vl")) vl_reg = r->handle;
        if (!strcmp(r->name, "vtype")) type_reg = r->handle;
        if (!strcmp(r->name, "a0")) n_reg = r->handle;
        if (!strcmp(r->name, "a6")) nc_reg = r->handle;
    }
    g_array_free(regs, true);
    regbuf = g_byte_array_new();
    if (!vl_reg || !type_reg || !n_reg || !nc_reg) abort();
}
static void boundary(unsigned cpu, void *data) {
    unsigned next = GPOINTER_TO_UINT(data);
    Counts *totals = qemu_plugin_scoreboard_find(score, cpu);
    for (unsigned i = 0; i < functions->len; ++i) fn(i)->snapshots[next - 1] = totals[i];
    phase = next;
}
static void kernel_entry(unsigned cpu, void *data) {
    (void) cpu;
    if (phase != 1 && phase != 2) return;
    Function *f = fn(GPOINTER_TO_UINT(data));
    uint64_t n = read_reg(n_reg);
    uint64_t columns = strstr(f->name, "ggml_gemv_") ? read_reg(nc_reg) : 1;
    if (!n || n % 32 || !columns || n > (1ULL << 30) || columns > (1ULL << 30)) abort();
    f->calls[phase - 1]++;
    // Count 32-weight blocks across all output columns (repacked or plain).
    f->blocks[phase - 1] += n / 32 * columns;
}
static void vector_exec(unsigned cpu, void *data) {
    (void) cpu;
    if (phase != 1 && phase != 2) return;
    Instruction *insn = data;
    uint64_t vl = 0, vt = 0;
    unsigned sew = 0;
    int lmul = 0;
    if (!insn->is_vset) {
        vl = read_reg(vl_reg);
        vt = read_reg(type_reg);
        if (vt >> 63) { fprintf(stderr, "R01 encountered vill\n"); abort(); }
        sew = 8U << ((vt >> 3) & 7);
        lmul = vt & 7;
        if (lmul >= 4) lmul -= 8;
    }
    // LMUL is encoded as a signed base-two exponent; vset has no data VL.
    if (insn->last_frequency && insn->last_phase == phase && insn->last_vt == vt && insn->last_vl == vl) {
        insn->last_frequency->count++;
        return;
    }
    char *key = g_strdup_printf("%u,%u,%s,%u,%d,%" PRIu64, phase, insn->fn, insn->op, sew, lmul, vl);
    Frequency *value = g_hash_table_lookup(frequencies, key);
    if (!value) {
        value = g_new0(Frequency, 1);
        g_hash_table_insert(frequencies, key, value);
    } else g_free(key);
    value->count++;
    insn->last_phase = phase;
    insn->last_vt = vt;
    insn->last_vl = vl;
    insn->last_frequency = value;
}
static void memory_access(unsigned cpu, qemu_plugin_meminfo_t info, uint64_t addr, void *data) {
    (void) cpu;
    (void) addr;
    if (phase != 1 && phase != 2) return;
    Instruction *insn = data;
    Function *f = fn(insn->fn);
    uint64_t bytes = 1ULL << qemu_plugin_mem_size_shift(info);
    if (qemu_plugin_mem_is_store(info)) f->stores[phase - 1][insn->memory] += bytes;
    else f->loads[phase - 1][insn->memory] += bytes;
}
static void translate(qemu_plugin_id_t id, struct qemu_plugin_tb *tb) {
    (void) id;
    for (size_t i = 0; i < qemu_plugin_tb_n_insns(tb); ++i) {
        struct qemu_plugin_insn *q = qemu_plugin_tb_get_insn(tb, i);
        uint64_t pc = qemu_plugin_insn_vaddr(q);
        unsigned index = find_function(pc);
        Function *f = fn(index);
        if (!strcmp(f->name, "r01_phase_prefill") || !strcmp(f->name, "r01_phase_decode") || !strcmp(f->name, "r01_phase_end")) {
            if (pc == f->start) {
                unsigned next = !strcmp(f->name, "r01_phase_prefill") ? 1 : (!strcmp(f->name, "r01_phase_decode") ? 2 : 3);
                qemu_plugin_register_vcpu_insn_exec_cb(q, boundary, QEMU_PLUGIN_CB_NO_REGS, GUINT_TO_POINTER(next));
            }
            continue;
        }
        uint32_t raw = 0;
        size_t size = qemu_plugin_insn_data(q, &raw, sizeof(raw));
        unsigned opcode = raw & 0x7f, width = (raw >> 12) & 7;
        bool vector_mem = size == 4 && (opcode == 0x07 || opcode == 0x27) && (width == 0 || width >= 5);
        bool vector = size == 4 && (opcode == 0x57 || vector_mem);
        bool vset = vector && opcode == 0x57 && width == 7;
        qemu_plugin_u64 count = {score, index * sizeof(Counts) + (vector ? offsetof(Counts, vector) : offsetof(Counts, scalar))};
        qemu_plugin_register_vcpu_insn_exec_inline_per_vcpu(q, QEMU_PLUGIN_INLINE_ADD_U64, count, 1);
        if (vset) {
            qemu_plugin_u64 config_count = {score, index * sizeof(Counts) + offsetof(Counts, vset)};
            qemu_plugin_register_vcpu_insn_exec_inline_per_vcpu(q, QEMU_PLUGIN_INLINE_ADD_U64, config_count, 1);
        }
        if (pc == f->start && (strstr(f->name, "ggml_vec_dot_q4_0_q8_0") || strstr(f->name, "ggml_vec_dot_q8_0_q8_0") ||
                              strstr(f->name, "ggml_gemv_q4_0_") || strstr(f->name, "ggml_gemv_q8_0_"))) {
            qemu_plugin_register_vcpu_insn_exec_cb(q, kernel_entry, QEMU_PLUGIN_CB_R_REGS, GUINT_TO_POINTER(index));
        }
        if (vector) {
            Instruction *insn = g_new0(Instruction, 1);
            insn->fn = index;
            insn->is_vset = vset;
            char *disas = qemu_plugin_insn_disas(q);
            insn->op = g_strndup(disas, strcspn(disas, " \t"));
            g_free(disas);
            qemu_plugin_register_vcpu_insn_exec_cb(q, vector_exec, QEMU_PLUGIN_CB_R_REGS, insn);
            if (vector_mem) {
                unsigned mop = (raw >> 26) & 3, nf = (raw >> 29) & 7, lumop = (raw >> 20) & 31;
                // Categories: unit stride, strided, indexed, segmented.
                insn->memory = (nf && !(mop == 0 && lumop == 8)) ? 3 : (mop == 2 ? 1 : (mop == 1 || mop == 3 ? 2 : 0));
                qemu_plugin_register_vcpu_mem_cb(q, memory_access, QEMU_PLUGIN_CB_NO_REGS, QEMU_PLUGIN_MEM_RW, insn);
            }
        }
    }
}
static void finish(qemu_plugin_id_t id, void *data) {
    (void) id;
    (void) data;
    if (phase != 3) { fprintf(stderr, "R01 missing phase boundaries; statistics invalid\n"); return; }
    char *path = g_strdup_printf("%s-functions.csv", output);
    FILE *out = fopen(path, "w");
    if (!out) abort();
    fprintf(out, "phase,symbol,scalar,vector,vset,calls,blocks,load_unit,load_strided,load_indexed,load_segmented,store_unit,store_strided,store_indexed,store_segmented\n");
    for (unsigned p = 0; p < 2; ++p) {
        for (unsigned i = 0; i < functions->len; ++i) {
            Function *f = fn(i);
            Counts a = f->snapshots[p], b = f->snapshots[p + 1];
            if (b.scalar == a.scalar && b.vector == a.vector) continue;
            fprintf(out, "%s,%s,%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64,
                    p ? "decode" : "prefill", f->name, b.scalar - a.scalar, b.vector - a.vector, b.vset - a.vset,
                    f->calls[p], f->blocks[p]);
            for (int m = 0; m < 4; ++m) fprintf(out, ",%" PRIu64, f->loads[p][m]);
            for (int m = 0; m < 4; ++m) fprintf(out, ",%" PRIu64, f->stores[p][m]);
            fputc('\n', out);
        }
    }
    fclose(out);
    g_free(path);
    path = g_strdup_printf("%s-vectors.csv", output);
    out = fopen(path, "w");
    if (!out) abort();
    fprintf(out, "phase,symbol,opcode,sew,lmul_log2,vl,count\n");
    GHashTableIter it;
    gpointer key, val;
    g_hash_table_iter_init(&it, frequencies);
    while (g_hash_table_iter_next(&it, &key, &val)) {
        unsigned p, f;
        int used;
        if (sscanf(key, "%u,%u,%n", &p, &f, &used) != 2) abort();
        fprintf(out, "%s,%s,%s,%" PRIu64 "\n", p == 1 ? "prefill" : "decode", fn(f)->name,
                (char *) key + used, ((Frequency *) val)->count);
    }
    fclose(out);
    fprintf(stderr, "R01_PROFILE_COMPLETE %s\n", output);
}
QEMU_PLUGIN_EXPORT int qemu_plugin_install(qemu_plugin_id_t id, const qemu_info_t *info, int argc, char **argv) {
    (void) info;
    char *symbols = NULL;
    for (int i = 0; i < argc; ++i) {
        if (g_str_has_prefix(argv[i], "symbols=")) symbols = argv[i] + 8;
        else if (g_str_has_prefix(argv[i], "output=")) output = g_strdup(argv[i] + 7);
        else return -1;
    }
    if (!symbols || !output) return -1;
    FILE *in = fopen(symbols, "r");
    if (!in) return -1;
    functions = g_array_new(false, true, sizeof(Function));
    Function unknown = {.name = "[unknown]"};
    g_array_append_val(functions, unknown);
    char name[4096];
    uint64_t start, size;
    while (fscanf(in, "%" SCNx64 " %" SCNx64 " %4095s", &start, &size, name) == 3) {
        Function f = {.start = start, .size = size, .name = g_strdup(name)};
        g_array_append_val(functions, f);
    }
    fclose(in);
    score = qemu_plugin_scoreboard_new(functions->len * sizeof(Counts));
    frequencies = g_hash_table_new_full(g_str_hash, g_str_equal, g_free, g_free);
    qemu_plugin_register_vcpu_init_cb(id, init);
    qemu_plugin_register_vcpu_tb_trans_cb(id, translate);
    qemu_plugin_register_atexit_cb(id, finish, NULL);
    return 0;
}
