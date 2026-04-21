# Processor System Reset — ui_clk domain.
# Mirrors FireSim's proc_sys_reset_1 (drives crossbar/dwidth m_axi_aresetn and
# ddr4.c0_ddr4_aresetn in the ui_clk domain, synchronized from external resetn).
if {![info exists proj_dir]} {
  error "proj_dir must be set before sourcing proc_sys_reset.tcl"
}

create_ip -name proc_sys_reset \
          -vendor xilinx.com \
          -library ip \
          -version 5.0 \
          -module_name proc_sys_reset_0 \
          -dir [file normalize "$proj_dir/ip"]

generate_target all [get_ips proc_sys_reset_0]
puts "INFO: proc_sys_reset_0 IP generated"
