#include "uart.h"

#include <fcntl.h>
#include <unistd.h>
#include <termios.h>
#include <sys/select.h>
#include <cerrno>
#include <cstring>
#include <ctime>
#include <cstdio>
#include <fstream>
#include <string>

namespace uart {

static int setup_serial(int fd) {
    struct termios tty;
    if (tcgetattr(fd, &tty) != 0) return -errno;
    cfsetispeed(&tty, B115200);
    cfsetospeed(&tty, B115200);
    tty.c_cflag = (tty.c_cflag & ~CSIZE) | CS8;
    tty.c_cflag &= ~(PARENB | PARODD | CSTOPB | CRTSCTS);
    tty.c_cflag |= CREAD | CLOCAL;
    tty.c_iflag &= ~(IXON | IXOFF | IXANY | ICRNL);
    tty.c_lflag  = 0;
    tty.c_oflag  = 0;
    tty.c_cc[VMIN]  = 0;
    tty.c_cc[VTIME] = 1;   // 0.1s read timeout
    return tcsetattr(fd, TCSANOW, &tty) == 0 ? 0 : -errno;
}

int collect(const std::string& uart_dev,
            const std::string& log_path,
            int timeout_sec,
            const std::string& sentinel) {
    int fd = open(uart_dev.c_str(), O_RDWR | O_NOCTTY | O_NONBLOCK);
    if (fd < 0) {
        perror("[uart] open");
        return -1;
    }
    // clear O_NONBLOCK after open
    fcntl(fd, F_SETFL, 0);
    setup_serial(fd);

    std::ofstream log(log_path, std::ios::out | std::ios::trunc);
    if (!log.is_open()) {
        fprintf(stderr, "[uart] cannot open log: %s\n", log_path.c_str());
        close(fd);
        return -1;
    }

    fprintf(stderr, "[uart] collecting from %s (timeout %ds)\n",
            uart_dev.c_str(), timeout_sec);

    time_t deadline = time(nullptr) + timeout_sec;
    std::string linebuf;
    char ch;
    int ret = 1;  // default: timeout

    while (time(nullptr) < deadline) {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(fd, &rfds);
        struct timeval tv = {1, 0};  // 1-second select timeout
        int sel = select(fd + 1, &rfds, nullptr, nullptr, &tv);
        if (sel < 0 && errno != EINTR) break;
        if (sel <= 0) continue;

        ssize_t n = ::read(fd, &ch, 1);
        if (n <= 0) continue;

        if (ch == '\r') continue;
        if (ch == '\n') {
            printf("%s\n", linebuf.c_str());
            log << linebuf << '\n';
            log.flush();
            if (!sentinel.empty() &&
                linebuf.find(sentinel) != std::string::npos) {
                ret = 0;
                break;
            }
            linebuf.clear();
        } else {
            linebuf += ch;
        }
    }

    if (!linebuf.empty()) {
        printf("%s\n", linebuf.c_str());
        log << linebuf << '\n';
    }

    close(fd);
    return ret;
}

}  // namespace uart
