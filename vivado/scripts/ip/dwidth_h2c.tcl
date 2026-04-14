# AXI Width Converter: XDMA 512-bit → HBM2 256-bit (H2C DMA path)
# Vivado IP: axi_dwidth_converter
# SI data width: 512, MI data width: 256, addr width: 33, id width: 4
# Must be sourced from main.tcl after $proj_dir is set.
if {![info exists proj_dir]} {
  error "proj_dir must be set before sourcing dwidth_h2c.tcl"
}

create_ip -name axi_dwidth_converter \
          -vendor xilinx.com \
          -library ip \
          -version 2.1 \
          -module_name axi_dwidth_converter_h2c \
          -dir [file normalize "$proj_dir/ip"]

set_property -dict [list \
  CONFIG.ADDR_WIDTH          {33} \
  CONFIG.SI_DATA_WIDTH       {512} \
  CONFIG.MI_DATA_WIDTH       {256} \
  CONFIG.SI_ID_WIDTH         {4} \
  CONFIG.ACLK_ASYNC          {0} \
] [get_ips axi_dwidth_converter_h2c]

generate_target all [get_ips axi_dwidth_converter_h2c]
puts "INFO: AXI dwidth converter (512->256) IP generated"
