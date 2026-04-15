# AXI Interconnect: 2 masters (H2C + SoC) → 1 slave (DDR4)
# axi_interconnect v1.7: ports are S00_AXI_*, S01_AXI_*, M00_AXI_*
if {![info exists proj_dir]} {
  error "proj_dir must be set before sourcing axi_ic.tcl"
}

create_ip -name axi_interconnect \
          -vendor xilinx.com \
          -library ip \
          -version 1.7 \
          -module_name axi_ic_ddr4 \
          -dir [file normalize "$proj_dir/ip"]

# Default NUM_SLAVE_PORTS is 2, which is what we need (H2C + SoC → DDR4)
# NUM_MASTER_PORTS is always 1 for axi_interconnect v1.7
# No set_property needed — defaults are correct

generate_target all [get_ips axi_ic_ddr4]
puts "INFO: AXI interconnect (2→1) IP generated"
