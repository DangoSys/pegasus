# DDR4 IP — AU280 on-board DDR4 RDIMM.
# Mirrors FireSim's create_bd_2021.1.tcl ddr4_0 configuration exactly.
if {![info exists proj_dir]} {
  error "proj_dir must be set before sourcing ddr4.tcl"
}

file mkdir [file normalize "$proj_dir/ip"]
create_ip -name ddr4 \
          -vendor xilinx.com \
          -library ip \
          -version 2.2 \
          -module_name ddr4_0 \
          -dir [file normalize "$proj_dir/ip"]

set_property -dict [list \
  CONFIG.C0.DDR4_AUTO_AP_COL_A3   {true} \
  CONFIG.C0.DDR4_AxiSelection     {true} \
  CONFIG.C0.DDR4_AxiDataWidth     {512} \
  CONFIG.C0.DDR4_AxiAddressWidth  {34} \
  CONFIG.C0.DDR4_AxiIDWidth       {4} \
  CONFIG.C0.DDR4_AxiNarrowBurst   {true} \
  CONFIG.C0.DDR4_InputClockPeriod {9996} \
  CONFIG.C0.DDR4_MCS_ECC          {false} \
  CONFIG.C0_CLOCK_BOARD_INTERFACE {sysclk0} \
  CONFIG.C0_DDR4_BOARD_INTERFACE  {ddr4_sdram_c0} \
  CONFIG.Debug_Signal             {Disable} \
  CONFIG.RESET_BOARD_INTERFACE    {resetn} \
] [get_ips ddr4_0]

generate_target all [get_ips ddr4_0]

# GLOBAL mode: DDR4 has deep submodule hierarchy (ddr4_0 → ddr4_0_ddr4 → ...),
# OOC mode fails in project mode. Inline all submodules during top-level synth.
set_property generate_synth_checkpoint false [get_files [get_property IP_FILE [get_ips ddr4_0]]]

puts "INFO: DDR4 IP generated (firesim-aligned)"
