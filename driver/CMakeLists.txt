cmake_minimum_required(VERSION 3.16)
project(pegasus-driver LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(pegasus-driver
    src/main.cc
    src/xdma.cc
    src/scu.cc
    src/uart.cc
)

target_include_directories(pegasus-driver PRIVATE src)

# libelf for reading ELF kernel binary
find_library(ELF_LIB elf REQUIRED)
target_link_libraries(pegasus-driver PRIVATE ${ELF_LIB})

install(TARGETS pegasus-driver DESTINATION bin)
