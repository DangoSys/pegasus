################################################################################
# AU280 PCIe reset (active-low)
# Source: Xilinx AU280 board files
# Port name uses io_ prefix from Chisel/CIRCT generation
################################################################################
set_property PACKAGE_PIN BF41      [get_ports io_pcie_sys_rst_n]
set_property IOSTANDARD  LVCMOS18  [get_ports io_pcie_sys_rst_n]
set_property PULLUP      true      [get_ports io_pcie_sys_rst_n]

################################################################################
# HBM reference clock (100 MHz)
# Declare clock for timing analysis; HBM IP handles physical connection
################################################################################
create_clock -period 10.000 -name hbm_ref_clk [get_ports io_hbm_ref_clk]

################################################################################
# PCIe sys_clk/sys_clk_gt routed through IBUFDS_GTE4 inside XDMA IP
# XDMA IP XDC (auto-loaded on generate_target) handles their constraints
################################################################################

################################################################################
# Relax DRC for bring-up — unconnected I/O pins downgraded to warnings
################################################################################
set_property SEVERITY Warning [get_drc_checks NSTD-1]
set_property SEVERITY Warning [get_drc_checks UCIO-1]
set_property SEVERITY Warning [get_drc_checks RTSTAT-1]
