# AXI Width+Clock Converter — 64b (axi_aclk) -> 512b (ui_clk), ACLK_ASYNC.
# Mirrors FireSim's axi_dwidth_converter_0 (SI 64b, MI 512b, async, FIFO).
if {![info exists proj_dir]} {
  error "proj_dir must be set before sourcing axi_dwidth.tcl"
}

create_ip -name axi_dwidth_converter \
          -vendor xilinx.com \
          -library ip \
          -version 2.1 \
          -module_name axi_dwidth \
          -dir [file normalize "$proj_dir/ip"]

set_property -dict [list \
  CONFIG.ACLK_ASYNC     {1} \
  CONFIG.FIFO_MODE      {2} \
  CONFIG.SI_DATA_WIDTH  {64} \
  CONFIG.MI_DATA_WIDTH  {512} \
  CONFIG.SI_ID_WIDTH    {4} \
  CONFIG.ADDR_WIDTH     {34} \
] [get_ips axi_dwidth]

generate_target all [get_ips axi_dwidth]
puts "INFO: axi_dwidth (64->512 ACLK_ASYNC) IP generated"
