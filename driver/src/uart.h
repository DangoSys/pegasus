#pragma once
#include <string>

// UART output collector — reads /dev/ttyUSB0 (or similar) and
// streams lines to stdout + a log file until timeout or sentinel string.

namespace uart {

// Read from `uart_dev` until `timeout_sec` elapses or `sentinel` appears
// in the output. Each line is written to `log_path` and echoed to stdout.
// Returns 0 if sentinel was found, 1 on timeout.
int collect(const std::string& uart_dev,
            const std::string& log_path,
            int timeout_sec,
            const std::string& sentinel = "");

}  // namespace uart
