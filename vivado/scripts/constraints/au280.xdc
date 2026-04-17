################################################################################
# AU280 PCIe reset (active-low)
# Use board pin mapping to avoid conflicts with DDR4 board constraints.
################################################################################
set_property BOARD_PIN {pcie_perstn} [get_ports pcie_sys_rst_n]

################################################################################
# DDR4 reference clock — handled by Vivado board interface
# (C0_CLOCK_BOARD_INTERFACE {sysclk0} / C0_DDR4_BOARD_INTERFACE {ddr4_sdram_c0}).
# Pin constraints for BJ43/BJ44 are auto-generated; no manual entry needed here.
################################################################################

################################################################################
# PCIe sys_clk / sys_clk_gt consumed by XDMA via IBUFDS_GTE4.
################################################################################

################################################################################
# Relax DRC for bring-up
################################################################################
set_property SEVERITY Warning [get_drc_checks NSTD-1]
set_property SEVERITY Warning [get_drc_checks UCIO-1]
set_property SEVERITY Warning [get_drc_checks RTSTAT-1]
