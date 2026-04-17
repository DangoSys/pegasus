// pegasus-driver — load and run workloads on AU280
//
// Memory layout (DDR AXI MM DMA mode):
//   pwrite offset = SoC paddr - SOC_DRAM_BASE (0x80000000)
//   kernel image loaded at DDR offset 0x200000 (SoC paddr 0x80200000)
//   rootfs loaded right after kernel, 4 MB aligned
//
// Subcommands:
//   load  --kernel <image>  --rootfs <img>
//         [--h2c /dev/xdma0_h2c_0]
//         [--kernel-offset <hex>]    (DDR offset for kernel, default: 0x200000)
//         [--rootfs-offset <hex>]    (DDR offset for rootfs, default: auto)
//
//   run   [--control /dev/xdma0_user]   (BAR0 → SCU AXI-Lite)
//         [--uart /dev/ttyUSB0]
//         [--log <path>]
//         [--timeout <seconds>]
//         [--sentinel <string>]

#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <sys/stat.h>

#include "xdma.h"
#include "scu.h"
#include "uart.h"

static const uint64_t SOC_DRAM_BASE = 0x80000000ULL;
static const uint64_t ROOTFS_ALIGN  = 4ULL * 1024 * 1024;  // 4 MB

static uint64_t align_up(uint64_t v, uint64_t align) {
    return (v + align - 1) & ~(align - 1);
}

static int cmd_load(int argc, char** argv) {
    std::string kernel, rootfs;
    std::string h2c_dev = "/dev/xdma0_h2c_0";
    uint64_t kernel_offset = 0x200000ULL;  // default: SoC paddr 0x80200000
    bool kernel_offset_set = false;
    uint64_t rootfs_offset = 0;
    bool rootfs_offset_set = false;

    for (int i = 0; i < argc; i++) {
        if      (!strcmp(argv[i], "--kernel")         && i+1 < argc) kernel = argv[++i];
        else if (!strcmp(argv[i], "--rootfs")          && i+1 < argc) rootfs = argv[++i];
        else if (!strcmp(argv[i], "--h2c")             && i+1 < argc) h2c_dev = argv[++i];
        else if (!strcmp(argv[i], "--kernel-offset")   && i+1 < argc) {
            kernel_offset = strtoull(argv[++i], nullptr, 0);
            kernel_offset_set = true;
        }
        else if (!strcmp(argv[i], "--rootfs-offset")   && i+1 < argc) {
            rootfs_offset = strtoull(argv[++i], nullptr, 0);
            rootfs_offset_set = true;
        }
    }

    if (kernel.empty() || rootfs.empty()) {
        fprintf(stderr, "load: --kernel and --rootfs are required\n");
        return 1;
    }

    // 1. Write kernel image (raw binary) to DDR at kernel_offset
    fprintf(stderr, "[load] writing kernel: %s -> DDR offset 0x%lx (SoC paddr 0x%lx)\n",
            kernel.c_str(), kernel_offset, kernel_offset + SOC_DRAM_BASE);
    int krc = xdma::write_raw(h2c_dev, kernel_offset, kernel);
    if (krc != 0) {
        fprintf(stderr, "[load] kernel write failed: %d\n", krc);
        return 1;
    }

    // 2. Auto-compute rootfs placement: right after kernel, 4 MB aligned
    if (!rootfs_offset_set) {
        struct stat st;
        if (stat(kernel.c_str(), &st) != 0) {
            fprintf(stderr, "[load] stat kernel failed\n");
            return 1;
        }
        uint64_t kernel_end = kernel_offset + static_cast<uint64_t>(st.st_size);
        rootfs_offset = align_up(kernel_end, ROOTFS_ALIGN);
        fprintf(stderr, "[load] auto rootfs DDR offset: 0x%lx (SoC paddr 0x%lx)\n",
                rootfs_offset, rootfs_offset + SOC_DRAM_BASE);
    }

    // 3. Write rootfs image
    fprintf(stderr, "[load] writing rootfs: %s\n", rootfs.c_str());
    int rc = xdma::write_raw(h2c_dev, rootfs_offset, rootfs);
    if (rc != 0) {
        fprintf(stderr, "[load] rootfs write failed: %d\n", rc);
        return 1;
    }

    fprintf(stderr, "[load] done — kernel @ SoC 0x%lx, rootfs @ SoC 0x%lx\n",
            kernel_offset + SOC_DRAM_BASE, rootfs_offset + SOC_DRAM_BASE);
    return 0;
}

static int cmd_run(int argc, char** argv) {
    std::string control_dev = "/dev/xdma0_control";
    std::string uart_dev    = "/dev/ttyUSB0";
    std::string log_path    = "/tmp/pegasus_uart.log";
    std::string sentinel;
    int timeout = 300;

    for (int i = 0; i < argc; i++) {
        if      (!strcmp(argv[i], "--control")  && i+1 < argc) control_dev = argv[++i];
        else if (!strcmp(argv[i], "--uart")     && i+1 < argc) uart_dev    = argv[++i];
        else if (!strcmp(argv[i], "--log")      && i+1 < argc) log_path    = argv[++i];
        else if (!strcmp(argv[i], "--sentinel") && i+1 < argc) sentinel    = argv[++i];
        else if (!strcmp(argv[i], "--timeout")  && i+1 < argc) timeout     = atoi(argv[++i]);
    }

    fprintf(stderr, "[run] releasing CPU via SCU (%s)\n", control_dev.c_str());
    if (scu::cpu_start(control_dev) != 0) {
        fprintf(stderr, "[run] SCU write failed\n");
        return 1;
    }

    fprintf(stderr, "[run] collecting UART → %s  (timeout %ds)\n",
            log_path.c_str(), timeout);
    int rc = uart::collect(uart_dev, log_path, timeout, sentinel);
    if (rc == 1)  fprintf(stderr, "[run] timed out\n");
    if (rc == 0)  fprintf(stderr, "[run] sentinel found, done\n");
    return rc == -1 ? 1 : 0;
}

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr,
            "Usage: pegasus-driver <load|run> [options]\n"
            "  load --kernel <elf> --rootfs <img> [--h2c <dev>] [--rootfs-offset <hex>]\n"
            "  run  [--control <dev>] [--uart <dev>] [--log <path>]"
                  " [--timeout <s>] [--sentinel <str>]\n");
        return 1;
    }
    if (!strcmp(argv[1], "load")) return cmd_load(argc - 2, argv + 2);
    if (!strcmp(argv[1], "run"))  return cmd_run(argc - 2, argv + 2);
    fprintf(stderr, "Unknown subcommand: %s\n", argv[1]);
    return 1;
}
