FLOW_GAPBS_VERSION = b5e3e19c2845f22fb338f4a4bc4b1ccee861d026
FLOW_GAPBS_SITE = $(call github,sbeamer,gapbs,$(FLOW_GAPBS_VERSION))
FLOW_GAPBS_LICENSE = BSD-3-Clause
FLOW_GAPBS_LICENSE_FILES = LICENSE

# Preserve GAPBS OpenMP code paths for comparison with the Rocket runs.
define FLOW_GAPBS_BUILD_CMDS
	$(TARGET_MAKE_ENV) $(MAKE) -C $(@D) CXX="$(TARGET_CXX)" \
		CXX_FLAGS="$(TARGET_CXXFLAGS) -std=c++11 -O3 -Wall -fopenmp $(TARGET_LDFLAGS)"
endef

define FLOW_GAPBS_INSTALL_TARGET_CMDS
	$(foreach prog,bc bfs cc cc_sv pr pr_spmv sssp tc converter,\
		$(INSTALL) -D -m 0755 $(@D)/$(prog) $(TARGET_DIR)/opt/gapbs/$(prog);)
	$(INSTALL) -D -m 0755 $(FLOW_GAPBS_PKGDIR)/gapbs-smoke \
		$(TARGET_DIR)/usr/bin/gapbs-smoke
endef

$(eval $(generic-package))
