FLOW_PERF_VERSION = 1
FLOW_PERF_SITE = $(FLOW_PERF_PKGDIR)/src
FLOW_PERF_SITE_METHOD = local

define FLOW_PERF_BUILD_CMDS
	$(TARGET_CC) $(TARGET_CFLAGS) -O2 -g -fno-omit-frame-pointer \
		-fno-optimize-sibling-calls -o $(@D)/flow-perf-workload \
		$(@D)/workload.c $(@D)/icache.S $(TARGET_LDFLAGS)
endef

define FLOW_PERF_INSTALL_TARGET_CMDS
	$(INSTALL) -D -m 0755 $(@D)/flow-perf-workload $(TARGET_DIR)/usr/bin/flow-perf-workload
	$(INSTALL) -D -m 0755 $(FLOW_PERF_PKGDIR)/flow-perf-smoke $(TARGET_DIR)/usr/bin/flow-perf-smoke
endef

$(eval $(generic-package))
