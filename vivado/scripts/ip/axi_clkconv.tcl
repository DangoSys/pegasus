# AXI Clock Converter: XDMA axi_aclk domain → DDR4 ui_clk domain
# Used on both H2C (XDMA→DDR4) and SoC (DigitalTop→DDR4) paths
if {![info exists proj_dir]} {
  error "proj_dir must be set before sourcing axi_clkconv.tcl"
}

create_ip -name axi_clock_converter \
          -vendor xilinx.com \
          -library ip \
          -version 2.1 \
          -module_name axi_clkconv_h2c \
          -dir [file normalize "$proj_dir/ip"]

set_property -dict [list \
  CONFIG.ADDR_WIDTH  {34} \
  CONFIG.DATA_WIDTH  {512} \
  CONFIG.ID_WIDTH    {4} \
] [get_ips axi_clkconv_h2c]

generate_target all [get_ips axi_clkconv_h2c]

create_ip -name axi_clock_converter \
          -vendor xilinx.com \
          -library ip \
          -version 2.1 \
          -module_name axi_clkconv_soc \
          -dir [file normalize "$proj_dir/ip"]

set_property -dict [list \
  CONFIG.ADDR_WIDTH  {34} \
  CONFIG.DATA_WIDTH  {512} \
  CONFIG.ID_WIDTH    {4} \
] [get_ips axi_clkconv_soc]

generate_target all [get_ips axi_clkconv_soc]
puts "INFO: AXI clock converters generated"
