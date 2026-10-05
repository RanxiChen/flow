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
QEMU_PLUGIN_EXPORT int qemu_plugin_install(qemu_plugin_id_t id, const qemu_info_t *info,
                                          int argc, char **argv) {
    (void) info;
    (void) argc;
    (void) argv;
    qemu_plugin_register_vcpu_init_cb(id, init);
    return 0;
}
