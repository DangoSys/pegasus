if { $argc < 4 } {
  puts "Usage: main.tcl <source_dir> <output_dir> <top> <part>"
  exit 1
}

set source_dir [lindex $argv 0]
set output_dir [lindex $argv 1]
set top_name   [lindex $argv 2]
set part_name  [lindex $argv 3]

if {![file isdirectory $source_dir]} {
  puts "ERROR: source_dir does not exist: $source_dir"
  exit 1
}

file mkdir $output_dir
set proj_dir [file normalize "$output_dir/project"]

# Clean up stale IP directories from previous runs to prevent XCI conflicts.
# Each build recreates the project; old IP dirs cause "IP name already in use" errors.
set ip_dir [file normalize "$proj_dir/ip"]
if {[file isdirectory $ip_dir]} {
  puts "INFO: removing stale IP directory: $ip_dir"
  file delete -force $ip_dir
}
file mkdir $proj_dir

create_project pegasus_build $proj_dir -part $part_name -force
set_property board_part xilinx.com:au280:part0:1.1 [current_project]
set_property target_language Verilog [current_project]
set_param general.maxThreads 32

# ── IP Generation ────────────────────────────────────────────────────────────
set SCRIPT_DIR [file normalize [file dirname [info script]]]
source "${SCRIPT_DIR}/ip/xdma.tcl"
source "${SCRIPT_DIR}/ip/ddr4.tcl"
source "${SCRIPT_DIR}/ip/dwidth_h2c.tcl"
source "${SCRIPT_DIR}/ip/axi_clkconv.tcl"
source "${SCRIPT_DIR}/ip/axi_ic.tcl"

# Add IP XCI files so Vivado tracks them for OOC synthesis
set xci_files [glob -nocomplain -directory [file normalize "$proj_dir/ip"] -type f */*.xci]
if {[llength $xci_files] > 0} {
  add_files -norecurse $xci_files
  puts "INFO: added [llength $xci_files] XCI file(s)"
} else {
  puts "WARNING: no XCI files found under $proj_dir/ip"
}

# Run OOC (out-of-context) synthesis for all IPs before top-level synth.
# synth_ip synthesises the IP in-process and registers the result with the project.
synth_ip [get_ips]

# ── RTL Sources ──────────────────────────────────────────────────────────────
set sv_files [glob -nocomplain -directory $source_dir *.sv]
set v_files  [glob -nocomplain -directory $source_dir *.v]
set rtl_files_raw [concat $sv_files $v_files]
set rtl_files {}
foreach f $rtl_files_raw {
  set fname [file tail $f]
  # Skip: DPI stubs, ClockSource, and Chipyard harness layers not needed with PegasusTop
  if {[regexp {DPI|ClockSource|PegasusHarness|ChipTop} $fname]} {
    puts "INFO: skip sim/harness file $f"
  } else {
    lappend rtl_files $f
  }
}

if {[llength $rtl_files] == 0} {
  puts "ERROR: no rtl files found in $source_dir"
  exit 1
}

# Add FPGA-safe replacements for simulation primitives
foreach stub_file [glob -nocomplain -directory "${SCRIPT_DIR}/rtl" *.sv *.v] {
  lappend rtl_files $stub_file
  puts "INFO: added FPGA stub: [file tail $stub_file]"
}

add_files -norecurse $rtl_files
set sv_files_filtered {}
foreach f $sv_files {
  set fname [file tail $f]
  if {![regexp {DPI|ClockSource|PegasusHarness|ChipTop} $fname]} { lappend sv_files_filtered $f }
}
foreach f [glob -nocomplain -directory "${SCRIPT_DIR}/rtl" *.sv] {
  lappend sv_files_filtered $f
}
if {[llength $sv_files_filtered] > 0} {
  set_property file_type SystemVerilog [get_files $sv_files_filtered]
}
update_compile_order -fileset sources_1

# ── Constraints ──────────────────────────────────────────────────────────────
read_xdc "${SCRIPT_DIR}/constraints/au280.xdc"

# ── Synthesis ────────────────────────────────────────────────────────────────
synth_design -top $top_name -part $part_name
write_checkpoint -force "$output_dir/post_synth.dcp"

# Connect dbg_hub/clk before opt_design if any debug cores exist.
if {[llength [get_debug_cores -quiet dbg_hub]] > 0} {
  set clk_pin [lindex [get_pins -hierarchical -quiet -filter {IS_CLOCK == 1 && NAME =~ shell/dwidth_h2c/*}] 0]
  if {$clk_pin ne ""} {
    set clk_net [get_nets -of_objects $clk_pin]
    puts "INFO: connecting dbg_hub/clk to $clk_net"
    connect_debug_port dbg_hub/clk [get_nets $clk_net]
  }
}

# ── Implementation ───────────────────────────────────────────────────────────
opt_design
place_design
route_design

# Re-apply DRC relaxation after route (some checks are only evaluated post-route)
set_property SEVERITY Warning [get_drc_checks RTSTAT-1]
set_property SEVERITY Warning [get_drc_checks RTSTAT-10]

# ── Reports & Outputs ────────────────────────────────────────────────────────
report_utilization   -file "$output_dir/utilization.rpt"
report_timing_summary -file "$output_dir/timing.rpt"
write_checkpoint -force "$output_dir/${top_name}.dcp"
write_bitstream  -force "$output_dir/${top_name}.bit"

puts "BITSTREAM_DONE $output_dir/${top_name}.bit"
