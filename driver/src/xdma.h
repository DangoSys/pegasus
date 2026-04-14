#pragma once
#include <cstdint>
#include <string>

// XDMA H2C (host-to-card) DMA interface
// Writes data to FPGA HBM2 via /dev/xdma0_h2c_0
// C2H (card-to-host) via /dev/xdma0_c2h_0 (for verification reads)

namespace xdma {

// Write `len` bytes from `buf` to FPGA HBM2 at `hbm2_offset`.
// hbm2_offset = paddr - 0x80000000  (SOC_TO_HBM2 mapping)
// Returns 0 on success, -errno on failure.
int write(const std::string& h2c_dev, uint64_t hbm2_offset,
          const void* buf, size_t len);

// Read `len` bytes from HBM2 at `hbm2_offset` into `buf`.
int read(const std::string& c2h_dev, uint64_t hbm2_offset,
         void* buf, size_t len);

// Write an ELF binary: parse load segments and write each PT_LOAD segment
// to HBM2 at the segment's physical address (minus SoC DRAM base 0x80000000).
// Returns the ELF entry point on success, or 0 on failure.
uint64_t write_elf(const std::string& h2c_dev, const std::string& elf_path);

// Write a raw image file starting at `hbm2_offset`.
int write_raw(const std::string& h2c_dev, uint64_t hbm2_offset,
              const std::string& img_path);

// Return the highest paddr+memsz of all PT_LOAD segments in an ELF.
// Used to compute where rootfs should be placed after the kernel.
uint64_t elf_max_paddr(const std::string& elf_path);

}  // namespace xdma
