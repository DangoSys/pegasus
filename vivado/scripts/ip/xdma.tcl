# XDMA IP — PCIe Gen3 x16, 512-bit AXI, 250 MHz
# Vivado 2021.1 / xdma v4.1
# Must be sourced from main.tcl after $proj_dir is set.
if {![info exists proj_dir]} {
  error "proj_dir must be set before sourcing xdma.tcl"
}

file mkdir [file normalize "$proj_dir/ip"]
create_ip -name xdma \
          -vendor xilinx.com \
          -library ip \
          -version 4.1 \
          -module_name xdma_0 \
          -dir [file normalize "$proj_dir/ip"]

set_property -dict [list \
  CONFIG.functional_mode          {AXI_Bridge} \
  CONFIG.mode_selection           {Basic} \
  CONFIG.pl_link_cap_max_link_width {X16} \
  CONFIG.pl_link_cap_max_link_speed {8.0_GT/s} \
  CONFIG.axi_data_width           {512_bit} \
  CONFIG.axisten_freq             {250} \
  CONFIG.axilite_master_en        {true} \
  CONFIG.axilite_master_size      {4} \
  CONFIG.pf0_bar0_size            {4} \
  CONFIG.pf0_bar0_scale           {Megabytes} \
] [get_ips xdma_0]

generate_target all [get_ips xdma_0]
puts "INFO: XDMA IP generated"
