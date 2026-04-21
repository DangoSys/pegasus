################################################################################
# AU280 board constraints for Pegasus.
# PCIe refclk, reset, and DDR4 pins are handled by Vivado board interfaces
# (pcie_refclk, pcie_perstn, sysclk0, ddr4_sdram_c0) set in IP configurations.
################################################################################

################################################################################
# Relax DRC for bring-up.
################################################################################
set_property SEVERITY Warning [get_drc_checks NSTD-1]
set_property SEVERITY Warning [get_drc_checks UCIO-1]
set_property SEVERITY Warning [get_drc_checks RTSTAT-1]
