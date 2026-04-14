#pragma once
#include <string>
#include <cstdint>

// SCU — System Control Unit
// Controls CPU reset via XDMA BAR0 AXI-Lite registers.
//
// Register map (BAR0, offset from BAR0 base):
//   0x000  ctrl[31:0]  — bit0: cpu_hold_reset
//                        1 = CPU held in reset (default after flash)
//                        0 = CPU running

namespace scu {

// Open the XDMA AXI-Lite control device (typically /dev/xdma0_control or
// mmap BAR0 from /sys/bus/pci/devices/<bdf>/resource0).
// Returns a handle (file descriptor) or -1 on error.
int open_bar0(const std::string& control_dev);

// Write a 32-bit register at `offset` within BAR0.
int write_reg(int fd, uint32_t offset, uint32_t value);

// Read a 32-bit register.
uint32_t read_reg(int fd, uint32_t offset);

void close_bar0(int fd);

// Convenience: release CPU from reset (write ctrl=0).
int cpu_start(const std::string& control_dev);

// Convenience: put CPU back into reset (write ctrl=1).
int cpu_reset(const std::string& control_dev);

static constexpr uint32_t SCU_CTRL_OFFSET = 0x0;
static constexpr uint32_t SCU_CPU_HOLD    = 0x1;

}  // namespace scu
