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
find_package(PkgConfig REQUIRED)
pkg_check_modules(LIBELF REQUIRED libelf)
target_include_directories(pegasus-driver PRIVATE ${LIBELF_INCLUDE_DIRS})
target_link_libraries(pegasus-driver PRIVATE ${LIBELF_LIBRARIES})
target_link_directories(pegasus-driver PRIVATE ${LIBELF_LIBRARY_DIRS})

install(TARGETS pegasus-driver DESTINATION bin)
