# AXI Crossbar: 2 slaves (H2C + SoC) → 1 master (DDR4)
# axi_crossbar v2.1: vector ports s_axi_*/m_axi_*, configurable width
# NUM_SI=2 slaves, NUM_MI=1 master, 64-bit data, 34-bit addr
if {![info exists proj_dir]} {
  error "proj_dir must be set before sourcing axi_ic.tcl"
}

create_ip -name axi_crossbar \
          -vendor xilinx.com \
          -library ip \
          -version 2.1 \
          -module_name axi_ic_ddr4 \
          -dir [file normalize "$proj_dir/ip"]

set_property -dict [list \
  CONFIG.NUM_SI   {2} \
  CONFIG.NUM_MI   {1} \
  CONFIG.DATA_WIDTH {64} \
  CONFIG.ADDR_WIDTH {34} \
  CONFIG.ID_WIDTH   {4} \
] [get_ips axi_ic_ddr4]

generate_target all [get_ips axi_ic_ddr4]
puts "INFO: AXI crossbar (2→1, 64-bit/34-bit) IP generated"
