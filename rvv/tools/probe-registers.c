// Capability probe only, not workload instruction statistics.
#include <glib.h>
#include <stdio.h>
#include <string.h>
#include <qemu-plugin.h>
QEMU_PLUGIN_EXPORT int qemu_plugin_version = QEMU_PLUGIN_VERSION;
static void init(qemu_plugin_id_t id, unsigned int cpu) {
    (void) id;
    (void) cpu;
    GArray *regs = qemu_plugin_get_registers();
    for (guint i = 0; i < regs->len; ++i) {
        qemu_plugin_reg_descriptor *r = &g_array_index(regs, qemu_plugin_reg_descriptor, i);
        fprintf(stderr, "REGISTER %s %s\n", r->name, r->feature ? r->feature : "");
    }
    g_array_free(regs, true);
}
static struct qemu_plugin_register *vl_reg, *type_reg;
static void read_vector(unsigned int cpu, void *data) {
    (void) cpu;
    (void) data;
    GByteArray *buf = g_byte_array_new();
    if (!vl_reg || !type_reg || !qemu_plugin_read_register(vl_reg, buf)) {
        fprintf(stderr, "VL_READ_FAILED\n");
        abort();
    }
    uint64_t vl = 0, type = 0;
    memcpy(&vl, buf->data, MIN(buf->len, sizeof(vl)));
    g_byte_array_set_size(buf, 0);
    if (!qemu_plugin_read_register(type_reg, buf)) abort();
    memcpy(&type, buf->data, MIN(buf->len, sizeof(type)));
    fprintf(stderr, "VECTOR_STATE vl=%lu vtype=%lu\n", vl, type);
    g_byte_array_free(buf, true);
}
static void capture_init(qemu_plugin_id_t id, unsigned int cpu) {
    init(id, cpu);
    GArray *regs = qemu_plugin_get_registers();
    for (guint i = 0; i < regs->len; ++i) {
        qemu_plugin_reg_descriptor *r = &g_array_index(regs, qemu_plugin_reg_descriptor, i);
        if (!strcmp(r->name, "vl")) vl_reg = r->handle;
        if (!strcmp(r->name, "vtype")) type_reg = r->handle;
    }
    g_array_free(regs, true);
}
static void translate(qemu_plugin_id_t id, struct qemu_plugin_tb *tb) {
    (void) id;
    for (size_t i = 0; i < qemu_plugin_tb_n_insns(tb); ++i) {
        struct qemu_plugin_insn *insn = qemu_plugin_tb_get_insn(tb, i);
        char *disas = qemu_plugin_insn_disas(insn);
        if (disas[0] == 'v' && strncmp(disas, "vset", 4)) {
            qemu_plugin_register_vcpu_insn_exec_cb(insn, read_vector, QEMU_PLUGIN_CB_R_REGS, NULL);
        }
        g_free(disas);
    }
}
QEMU_PLUGIN_EXPORT int qemu_plugin_install(qemu_plugin_id_t id, const qemu_info_t *info,
                                          int argc, char **argv) {
    (void) info;
    (void) argc;
    (void) argv;
    qemu_plugin_register_vcpu_init_cb(id, capture_init);
    qemu_plugin_register_vcpu_tb_trans_cb(id, translate);
    return 0;
}
