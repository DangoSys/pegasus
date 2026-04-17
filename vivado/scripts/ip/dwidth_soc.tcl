# AXI Width Converter: SoC chip_mem 64-bit -> DDR4 512-bit
if {![info exists proj_dir]} {
  error "proj_dir must be set before sourcing dwidth_soc.tcl"
}

create_ip -name axi_dwidth_converter \
          -vendor xilinx.com \
          -library ip \
          -version 2.1 \
          -module_name axi_dwidth_converter_soc \
          -dir [file normalize "$proj_dir/ip"]

set_property -dict [list \
  CONFIG.ADDR_WIDTH          {34} \
  CONFIG.SI_DATA_WIDTH       {64} \
  CONFIG.MI_DATA_WIDTH       {512} \
  CONFIG.SI_ID_WIDTH         {4} \
  CONFIG.ACLK_ASYNC          {0} \
] [get_ips axi_dwidth_converter_soc]

generate_target all [get_ips axi_dwidth_converter_soc]
puts "INFO: AXI dwidth converter SoC (64->512) IP generated"
