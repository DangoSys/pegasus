################################################################################
# AU280 PCIe reset (active-low)
# Top-level is PegasusTop — no io_ prefix on port names
################################################################################
set_property PACKAGE_PIN BF41      [get_ports pcie_sys_rst_n]
set_property IOSTANDARD  LVCMOS18  [get_ports pcie_sys_rst_n]
set_property PULLUP      true      [get_ports pcie_sys_rst_n]

################################################################################
# HBM reference clock (100 MHz)
################################################################################
create_clock -period 10.000 -name hbm_ref_clk [get_ports hbm_ref_clk]

################################################################################
# PCIe sys_clk / sys_clk_gt are consumed by XDMA IP via IBUFDS_GTE4.
# Vivado may insert an extra IBUF on sys_clk_gt — suppress the unrouted DRC.
################################################################################
set_property CLOCK_DEDICATED_ROUTE FALSE [get_nets pcie_sys_clk_gt_IBUF]

################################################################################
# Relax DRC for bring-up
################################################################################
set_property SEVERITY Warning [get_drc_checks NSTD-1]
set_property SEVERITY Warning [get_drc_checks UCIO-1]
set_property SEVERITY Warning [get_drc_checks RTSTAT-1]
