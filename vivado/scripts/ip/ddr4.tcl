# DDR4 IP — AU280 on-board DDR4 RDIMM, configured via board interface
# sysclk0 (100 MHz diff) → C0_SYS_CLK; ddr4_sdram_c0 → C0_DDR4
# Board interface auto-sets: MemoryType=RDIMMs, MemoryPart, DataWidth=72 (ECC), timing
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
  CONFIG.C0_CLOCK_BOARD_INTERFACE   {sysclk0} \
  CONFIG.C0_DDR4_BOARD_INTERFACE    {ddr4_sdram_c0} \
  CONFIG.C0.DDR4_AUTO_AP_COL_A3     {true} \
  CONFIG.C0.DDR4_MCS_ECC            {false} \
] [get_ips ddr4_0]

generate_target all [get_ips ddr4_0]
puts "INFO: DDR4 IP generated (AU280 board interface, RDIMM ECC)"
