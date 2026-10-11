FLOW_COREMARK_VERSION = 1.01
FLOW_COREMARK_SOURCE = coremark-$(FLOW_COREMARK_VERSION).tar.gz
FLOW_COREMARK_SITE = $(call github,eembc,coremark,v$(FLOW_COREMARK_VERSION))
FLOW_COREMARK_LICENSE = Apache-2.0
FLOW_COREMARK_LICENSE_FILES = LICENSE.md

define FLOW_COREMARK_BUILD_CMDS
	$(TARGET_MAKE_ENV) $(MAKE) -C $(@D) CC="$(TARGET_CC)" \
		PORT_CFLAGS="$(TARGET_CFLAGS) -O3" \
		PORT_DIR=linux$(if $(BR2_ARCH_IS_64),64) EXE= link
endef

define FLOW_COREMARK_INSTALL_TARGET_CMDS
	$(INSTALL) -D -m 0755 $(@D)/coremark $(TARGET_DIR)/usr/bin/coremark
	$(INSTALL) -D -m 0644 $(@D)/LICENSE.md \
		$(TARGET_DIR)/usr/share/licenses/coremark/LICENSE.md
endef

$(eval $(generic-package))
