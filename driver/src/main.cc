// pegasus-driver — load and run workloads on AU280
//
// Subcommands:
//   load  --kernel <elf>  --rootfs <img>
//         [--h2c /dev/xdma0_h2c_0]
//         [--rootfs-offset <hex>]   (HBM2 offset for rootfs, default: auto after kernel)
//
//   run   [--control /dev/xdma0_control]
//         [--uart /dev/ttyUSB0]
//         [--log <path>]
//         [--timeout <seconds>]
//         [--sentinel <string>]     (stop collecting when this appears in UART output)

#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>

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
    uint64_t rootfs_offset = 0;
    bool rootfs_offset_set = false;

    for (int i = 0; i < argc; i++) {
        if      (!strcmp(argv[i], "--kernel")         && i+1 < argc) kernel = argv[++i];
        else if (!strcmp(argv[i], "--rootfs")          && i+1 < argc) rootfs = argv[++i];
        else if (!strcmp(argv[i], "--h2c")             && i+1 < argc) h2c_dev = argv[++i];
        else if (!strcmp(argv[i], "--rootfs-offset")   && i+1 < argc) {
            rootfs_offset = strtoull(argv[++i], nullptr, 0);
            rootfs_offset_set = true;
        }
    }

    if (kernel.empty() || rootfs.empty()) {
        fprintf(stderr, "load: --kernel and --rootfs are required\n");
        return 1;
    }

    // 1. Write kernel ELF segments to HBM2
    fprintf(stderr, "[load] writing kernel ELF: %s\n", kernel.c_str());
    uint64_t entry = xdma::write_elf(h2c_dev, kernel);
    if (entry == 0) {
        fprintf(stderr, "[load] ELF write failed\n");
        return 1;
    }

    // 2. Auto-compute rootfs placement: right after last ELF segment, 4 MB aligned
    if (!rootfs_offset_set) {
        uint64_t elf_end_paddr = xdma::elf_max_paddr(kernel);
        uint64_t elf_end_hbm2  = elf_end_paddr > SOC_DRAM_BASE
                                  ? elf_end_paddr - SOC_DRAM_BASE : 0;
        rootfs_offset = align_up(elf_end_hbm2, ROOTFS_ALIGN);
        fprintf(stderr, "[load] auto rootfs HBM2 offset: 0x%lx (SoC paddr 0x%lx)\n",
                rootfs_offset, rootfs_offset + SOC_DRAM_BASE);
    }

    // 3. Write rootfs image
    fprintf(stderr, "[load] writing rootfs: %s\n", rootfs.c_str());
    int rc = xdma::write_raw(h2c_dev, rootfs_offset, rootfs);
    if (rc != 0) {
        fprintf(stderr, "[load] rootfs write failed: %d\n", rc);
        return 1;
    }

    fprintf(stderr, "[load] done — kernel entry=0x%lx\n", entry);
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
