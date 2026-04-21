# AXI Crossbar — 2 slaves (XDMA DMA + SoC mem_axi4) -> 1 master (DDR4 dwidth).
# 64-bit data, 32-bit address, 4-bit ID.
# Uses GLOBAL synth_checkpoint_mode so top-level synth resolves submodules directly.
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
  CONFIG.NUM_SI             {2} \
  CONFIG.NUM_MI             {1} \
  CONFIG.DATA_WIDTH         {64} \
  CONFIG.ADDR_WIDTH         {32} \
  CONFIG.ID_WIDTH           {4} \
  CONFIG.ADDR_RANGES        {1} \
  CONFIG.M00_A00_BASE_ADDR  {0x0000000000000000} \
  CONFIG.M00_A00_ADDR_WIDTH {32} \
] [get_ips axi_ic_ddr4]

generate_target all [get_ips axi_ic_ddr4]

# Use GLOBAL mode so crossbar submodules (axi_crossbar_v2_1_*) are visible
# during top-level synth. Standalone create_ip allows this property.
set_property generate_synth_checkpoint false [get_files [get_property IP_FILE [get_ips axi_ic_ddr4]]]
puts "INFO: axi_ic_ddr4 (2->1 crossbar, 64b/32b) IP generated"
