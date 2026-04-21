################################################################################
# AU280 PCIe reset (active-low, board pcie_perstn pin).
################################################################################
set_property BOARD_PIN {pcie_perstn} [get_ports pcie_sys_rst_n]

################################################################################
# DDR4 reference clock — handled by Vivado board interface
# (C0_CLOCK_BOARD_INTERFACE {sysclk0} / C0_DDR4_BOARD_INTERFACE {ddr4_sdram_c0}).
################################################################################

################################################################################
# PCIe sys_clk / sys_clk_gt: routed to the PCIe refclk pads by Vivado based on
# board_part. Consumed by XDMA (via IBUFDS_GTE4 inside PegasusShell).
################################################################################

################################################################################
# Relax DRC for bring-up.
################################################################################
set_property SEVERITY Warning [get_drc_checks NSTD-1]
set_property SEVERITY Warning [get_drc_checks UCIO-1]
set_property SEVERITY Warning [get_drc_checks RTSTAT-1]
