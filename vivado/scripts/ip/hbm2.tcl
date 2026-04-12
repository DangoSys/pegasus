# HBM2 IP — Stack 0, MC0, 4GB, Vivado 2021.1 / hbm v1.0
# Must be sourced from main.tcl after $proj_dir is set.
if {![info exists proj_dir]} {
  error "proj_dir must be set before sourcing hbm2.tcl"
}

file mkdir [file normalize "$proj_dir/ip"]
create_ip -name hbm \
          -vendor xilinx.com \
          -library ip \
          -version 1.0 \
          -module_name hbm_0 \
          -dir [file normalize "$proj_dir/ip"]

set_property -dict [list \
  CONFIG.USER_HBM_DENSITY         {4GB} \
  CONFIG.USER_HBM_STACK           {1} \
  CONFIG.USER_MC_ENABLE_00        {TRUE} \
  CONFIG.USER_MC_ENABLE_01        {FALSE} \
  CONFIG.USER_MC_ENABLE_02        {FALSE} \
  CONFIG.USER_MC_ENABLE_03        {FALSE} \
  CONFIG.USER_MC_ENABLE_04        {FALSE} \
  CONFIG.USER_MC_ENABLE_05        {FALSE} \
  CONFIG.USER_MC_ENABLE_06        {FALSE} \
  CONFIG.USER_MC_ENABLE_07        {FALSE} \
  CONFIG.USER_CLK_SEL_LIST0       {AXI_01_ACLK} \
] [get_ips hbm_0]

generate_target all [get_ips hbm_0]
puts "INFO: HBM2 IP generated"
