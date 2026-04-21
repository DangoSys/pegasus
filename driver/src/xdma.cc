#include "xdma.h"

#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <cerrno>
#include <cstring>
#include <vector>
#include <cstdio>
#include <algorithm>

#include <libelf.h>
#include <gelf.h>

namespace xdma {

static const uint64_t SOC_DRAM_BASE = 0x80000000ULL;

// Low-level pwrite wrapper with retry on short writes
static int pwrite_all(int fd, const void* buf, size_t len, off_t offset) {
    const char* p = static_cast<const char*>(buf);
    size_t remaining = len;
    while (remaining > 0) {
        ssize_t n = pwrite(fd, p, remaining, offset);
        if (n < 0) return -errno;
        p += n;
        offset += n;
        remaining -= n;
    }
    return 0;
}

static int pread_all(int fd, void* buf, size_t len, off_t offset) {
    char* p = static_cast<char*>(buf);
    size_t remaining = len;
    while (remaining > 0) {
        ssize_t n = pread(fd, p, remaining, offset);
        if (n <= 0) return n < 0 ? -errno : -EIO;
        p += n;
        offset += n;
        remaining -= n;
    }
    return 0;
}

int write(const std::string& h2c_dev, uint64_t hbm2_offset,
          const void* buf, size_t len) {
    int fd = open(h2c_dev.c_str(), O_RDWR);
    if (fd < 0) return -errno;
    int rc = pwrite_all(fd, buf, len, static_cast<off_t>(hbm2_offset));
    close(fd);
    return rc;
}

int read(const std::string& c2h_dev, uint64_t hbm2_offset,
         void* buf, size_t len) {
    int fd = open(c2h_dev.c_str(), O_RDONLY);
    if (fd < 0) return -errno;
    int rc = pread_all(fd, buf, len, static_cast<off_t>(hbm2_offset));
    close(fd);
    return rc;
}

uint64_t write_elf(const std::string& h2c_dev, const std::string& elf_path) {
    if (elf_version(EV_CURRENT) == EV_NONE) {
        fprintf(stderr, "[xdma] libelf init failed: %s\n", elf_errmsg(-1));
        return 0;
    }

    int fd = open(elf_path.c_str(), O_RDONLY);
    if (fd < 0) {
        perror("[xdma] open elf");
        return 0;
    }

    Elf* elf = elf_begin(fd, ELF_C_READ, nullptr);
    if (!elf) {
        fprintf(stderr, "[xdma] elf_begin: %s\n", elf_errmsg(-1));
        close(fd);
        return 0;
    }

    GElf_Ehdr ehdr;
    if (!gelf_getehdr(elf, &ehdr)) {
        fprintf(stderr, "[xdma] gelf_getehdr: %s\n", elf_errmsg(-1));
        elf_end(elf);
        close(fd);
        return 0;
    }

    uint64_t entry = ehdr.e_entry;
    size_t n_phdr = 0;
    elf_getphdrnum(elf, &n_phdr);

    int xdma_fd = open(h2c_dev.c_str(), O_RDWR);
    if (xdma_fd < 0) {
        perror("[xdma] open h2c");
        elf_end(elf);
        close(fd);
        return 0;
    }

    for (size_t i = 0; i < n_phdr; i++) {
        GElf_Phdr phdr;
        if (!gelf_getphdr(elf, static_cast<int>(i), &phdr)) continue;
        if (phdr.p_type != PT_LOAD) continue;
        if (phdr.p_filesz == 0) continue;

        uint64_t paddr = phdr.p_paddr;
        if (paddr < SOC_DRAM_BASE) {
            fprintf(stderr, "[xdma] segment paddr 0x%lx < DRAM base, skipping\n", paddr);
            continue;
        }
        uint64_t hbm2_off = paddr - SOC_DRAM_BASE;

        fprintf(stderr, "[xdma] ELF segment: paddr=0x%lx hbm2=0x%lx size=0x%lx\n",
                paddr, hbm2_off, phdr.p_filesz);

        // Read segment data from ELF file
        std::vector<char> seg(phdr.p_filesz, 0);
        if (pread(fd, seg.data(), phdr.p_filesz, static_cast<off_t>(phdr.p_offset))
                != static_cast<ssize_t>(phdr.p_filesz)) {
            perror("[xdma] pread segment");
            close(xdma_fd);
            elf_end(elf);
            close(fd);
            return 0;
        }

        // Zero-fill memsz > filesz (BSS)
        if (phdr.p_memsz > phdr.p_filesz) {
            size_t bss = phdr.p_memsz - phdr.p_filesz;
            std::vector<char> zeros(bss, 0);
            uint64_t bss_off = hbm2_off + phdr.p_filesz;
            if (pwrite_all(xdma_fd, zeros.data(), bss, static_cast<off_t>(bss_off)) != 0) {
                perror("[xdma] write BSS");
            }
        }

        if (pwrite_all(xdma_fd, seg.data(), phdr.p_filesz,
                       static_cast<off_t>(hbm2_off)) != 0) {
            perror("[xdma] write segment");
            close(xdma_fd);
            elf_end(elf);
            close(fd);
            return 0;
        }
    }

    close(xdma_fd);
    elf_end(elf);
    close(fd);
    fprintf(stderr, "[xdma] ELF loaded, entry=0x%lx\n", entry);
    return entry;
}

int write_raw(const std::string& h2c_dev, uint64_t hbm2_offset,
              const std::string& img_path) {
    // Get image size
    struct stat st;
    if (stat(img_path.c_str(), &st) != 0) return -errno;
    size_t img_size = static_cast<size_t>(st.st_size);

    int img_fd = open(img_path.c_str(), O_RDONLY);
    if (img_fd < 0) return -errno;

    int xdma_fd = open(h2c_dev.c_str(), O_RDWR);
    if (xdma_fd < 0) { close(img_fd); return -errno; }

    // Use smaller chunks to avoid long single H2C transactions timing out.
    const size_t CHUNK = 1 * 1024 * 1024;
    std::vector<char> buf(CHUNK);
    size_t written = 0;
    int rc = 0;

    while (written < img_size) {
        size_t to_read = std::min(CHUNK, img_size - written);
        ssize_t n = ::read(img_fd, buf.data(), to_read);
        if (n <= 0) { rc = -EIO; break; }
        rc = pwrite_all(xdma_fd, buf.data(), static_cast<size_t>(n),
                        static_cast<off_t>(hbm2_offset + written));
        if (rc != 0) {
            fprintf(stderr,
                    "[xdma] write failed: rc=%d offset=0x%lx chunk=0x%zx\n",
                    rc, hbm2_offset + written, static_cast<size_t>(n));
            break;
        }
        written += static_cast<size_t>(n);
        fprintf(stderr, "\r[xdma] write: %zu/%zu MB",
                written / (1024*1024), img_size / (1024*1024));
    }
    fprintf(stderr, "\n");

    close(xdma_fd);
    close(img_fd);
    return rc;
}

uint64_t elf_max_paddr(const std::string& elf_path) {
    if (elf_version(EV_CURRENT) == EV_NONE) return 0;
    int fd = open(elf_path.c_str(), O_RDONLY);
    if (fd < 0) return 0;
    Elf* elf = elf_begin(fd, ELF_C_READ, nullptr);
    if (!elf) { close(fd); return 0; }
    size_t n = 0;
    elf_getphdrnum(elf, &n);
    uint64_t max_end = SOC_DRAM_BASE;
    for (size_t i = 0; i < n; i++) {
        GElf_Phdr ph;
        if (!gelf_getphdr(elf, static_cast<int>(i), &ph)) continue;
        if (ph.p_type != PT_LOAD) continue;
        uint64_t end = ph.p_paddr + ph.p_memsz;
        if (end > max_end) max_end = end;
    }
    elf_end(elf);
    close(fd);
    return max_end;
}

}  // namespace xdma
