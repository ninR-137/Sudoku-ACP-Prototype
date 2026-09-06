# MCAS Sudoku Solver — Android Integration Documentation

This document explains how the **MCAS (Multithreaded Ant Colony System)** C++ solver is integrated into the Android application via the Java Native Interface (JNI).

## 1. System Architecture

The solver integration follows a standard Android NDK architecture:

- **Java Layer (`SolverActivity.java`)**: Handles the Sudoku grid UI and user interactions.
- **JNI Bridge (`native-lib.cpp`)**: A C++ wrapper that translates Java types (Strings) into native C++ objects (`Board`) and calls the solver.
- **Native Engine (`src/`)**: The core C++ implementation of the MCAS and Backtracking algorithms.
- **Build System (`CMakeLists.txt`)**: Configures the NDK to compile the C++ source files into a shared library (`libnative-lib.so`).

---

## 2. Key Components

### JNI Bridge: `native-lib.cpp`
The primary entry point from Java is the `solveSudokuNative` function. It performs the following:
1.  **Data Marshalling**: Converts the `jstring` puzzle input (e.g., `53..7....`) into a C++ `std::string`.
2.  **Object Initialization**: Instantiates a native `Board` object.
3.  **Solver Selection**: Based on the `algorithm` parameter:
    - `0`: Standard Ant Colony System (ACS)
    - `1`: Backtracking Search
    - `2`: **MCAS** (Multithreaded ACS with inter-colony communication)
4.  **Execution**: Runs the solver with a 10-second timeout.
5.  **Result Mapping**: Converts the solved `Board` back into a flat string for the Java UI.

### Compilation Fixes
The original MCAS source code was adapted for the Android NDK (Clang compiler):
- **`std::random_shuffle`** (deprecated in C++14) was replaced with **`std::shuffle`** and `std::mt19937` in `simulatedannealing.cpp` and `parallelsudokuantsystem.cpp` to ensure modern compiler compatibility.

---

## 3. Build Requirements

- **Android NDK**: Version 21.0.0 or higher.
- **CMake**: Version 3.22.1 or higher.
- **C++ Standard**: Compiled using `-std=c++17` for modern features.

To rebuild the native library, perform a **Gradle Sync** in Android Studio. Gradle will automatically invoke CMake and the NDK to produce the necessary `.so` files for all supported architectures (arm64-v8a, armeabi-v7a, x86, x86_64).

---

## 4. Usage in Java

### Initialization
The library is loaded once when the `SolverActivity` starts:
```java
static {
    System.loadLibrary("native-lib");
}
```

### Calling the Solver
```java
// puzzle: string of numbers and '.'
// algorithm: 2 (MCAS)
// threads: 4 (number of parallel CPU cores to use)
String solution = solveSudokuNative(puzzle, 2, 4);
```

### String Formats
The solver handles multi-size grids using specific character sets:
- **9x9**: `1-9`
- **16x16**: `0-f` (hexadecimal)
- **25x25**: `a-y`

---

## 5. Performance Tips
- **MCAS** is the recommended algorithm for 16x16 and 25x25 grids as it leverages parallel processing.
- For standard 9x9 grids, **Backtracking** (alg `1`) is often instantaneous.
- Increasing the `threads` parameter beyond 4 may not yield significant gains on most mobile processors and can increase battery drain.
