cmake_minimum_required(VERSION 3.22.1)
project("v2_sudoku_acp_android")

# Path to the MCAS source files
set(src_dir src)

# Collect all C++ source files from the MCAS src directory
file(GLOB_RECURSE SOURCES "${src_dir}/*.cpp")

# Create the native library
# Includes your JNI bridge (native-lib.cpp) and all solver source files
# Note: we exclude solvermain.cpp if it exists to avoid multiple main functions,
# but shared library doesn't have a main anyway.
# However, it's safer to be explicit.
list(FILTER SOURCES EXCLUDE REGEX "solvermain.cpp")

add_library(native-lib SHARED native-lib.cpp ${SOURCES})

# Link the Android logging library
find_library(log-lib log)
target_link_libraries(native-lib ${log-lib})
