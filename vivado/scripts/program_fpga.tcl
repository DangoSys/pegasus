# program_fpga.tcl — Flash AU280 bitstream via hw_server
# Adapted from FireSim xilinx_alveo_u250/scripts/program_fpga.tcl
#
# Usage (called by Vivado batch mode):
#   vivado -mode batch -source program_fpga.tcl \
#          -tclargs -serial <hw_target_url_or_serial> -bitstream_path <path.bit>
#
# hw_target_url examples:
#   localhost:3121/xilinx_tcf/Xilinx/1234A   (full URL from get_hw_targets)
#   1234A                                      (serial substring match)

array set options {
  -serial         ""
  -bitstream_path ""
  -probes_path    ""
}

for {set i 0} {$i < $argc} {incr i 2} {
  set arg [lindex $argv $i]
  set val [lindex $argv [expr {$i + 1}]]
  if {[info exists options($arg)]} {
    set options($arg) $val
    puts "INFO: set $arg = $val"
  } else {
    puts "INFO: skip unknown arg $arg"
  }
}

set serial         $options(-serial)
set bitstream_path $options(-bitstream_path)
set probes_path    $options(-probes_path)

if {$bitstream_path eq ""} {
  puts "ERROR: -bitstream_path is required"
  exit 1
}
if {![file exists $bitstream_path]} {
  puts "ERROR: bitstream not found: $bitstream_path"
  exit 1
}

puts "INFO: bitstream = $bitstream_path"
puts "INFO: serial    = $serial"

# Suppress hw_server console-server warnings on some setups
set_param labtools.enable_cs_server false

open_hw_manager
connect_hw_server -allow_non_jtag

# Close the default hw_target that Vivado opens automatically
catch { close_hw_target }

# Find the hw_target whose URL contains the requested serial substring.
# If serial is empty, pick the first available target.
set hw_targets [get_hw_targets]
if {[llength $hw_targets] == 0} {
  puts "ERROR: no hw_targets found — is hw_server running?"
  exit 1
}

set chosen ""
if {$serial eq ""} {
  set chosen [lindex $hw_targets 0]
  puts "INFO: no serial specified, using first target: $chosen"
} else {
  foreach t $hw_targets {
    if {[string first $serial $t] != -1} {
      set chosen $t
      break
    }
  }
}

if {$chosen eq ""} {
  puts "ERROR: serial '$serial' not found among hw_targets:"
  foreach t $hw_targets { puts "  $t" }
  exit 1
}

puts "INFO: programming hw_target: $chosen"
open_hw_target $chosen

set hw_dev [lindex [get_hw_devices] 0]
set_property PROBES.FILE      $probes_path $hw_dev
set_property FULL_PROBES.FILE $probes_path $hw_dev
set_property PROGRAM.FILE     $bitstream_path $hw_dev

program_hw_devices $hw_dev
refresh_hw_device  $hw_dev

close_hw_target
puts "INFO: programming complete"
exit 0
