#include "scu.h"

#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <cerrno>
#include <cstdio>

// XDMA exposes the AXI-Lite BAR0 through /dev/xdma0_control (char device).
// Reads and writes are pread/pwrite at the register offset.
// BAR0 size is 4 MB as configured in xdma.tcl (pf0_bar0_size = 4 MB).

namespace scu {

static const size_t BAR0_SIZE = 4 * 1024 * 1024;

int open_bar0(const std::string& control_dev) {
    int fd = open(control_dev.c_str(), O_RDWR);
    if (fd < 0) {
        perror("[scu] open control");
    }
    return fd;
}

int write_reg(int fd, uint32_t offset, uint32_t value) {
    ssize_t n = pwrite(fd, &value, sizeof(value), static_cast<off_t>(offset));
    if (n != sizeof(value)) return -errno;
    return 0;
}

uint32_t read_reg(int fd, uint32_t offset) {
    uint32_t v = 0;
    pread(fd, &v, sizeof(v), static_cast<off_t>(offset));
    return v;
}

void close_bar0(int fd) {
    close(fd);
}

int cpu_start(const std::string& control_dev) {
    int fd = open_bar0(control_dev);
    if (fd < 0) return -errno;
    int rc = write_reg(fd, SCU_CTRL_OFFSET, 0x0);
    close_bar0(fd);
    if (rc == 0)
        fprintf(stderr, "[scu] CPU released from reset\n");
    else
        fprintf(stderr, "[scu] cpu_start failed: %d\n", rc);
    return rc;
}

int cpu_reset(const std::string& control_dev) {
    int fd = open_bar0(control_dev);
    if (fd < 0) return -errno;
    int rc = write_reg(fd, SCU_CTRL_OFFSET, SCU_CPU_HOLD);
    close_bar0(fd);
    return rc;
}

}  // namespace scu
