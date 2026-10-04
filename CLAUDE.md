# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Thesis prototype: an Android app (Java, single `:app` module, package `com.example.v2_sudoku_acp_android`) that photographs a Sudoku grid (9x9, 16x16 or 25x25), extracts it with OpenCV, reads the digits with a TFLite classifier, and solves it with the **MCAS** C++ solver (multithreaded Ant Colony System + constraint propagation + simulated annealing) through JNI.

## Commands

`gradlew` is checked in without the executable bit, so invoke it through `sh` (or `chmod +x gradlew` first). `local.properties` must point `sdk.dir` at an Android SDK with NDK and CMake 3.22.1 installed.

```bash
sh gradlew assembleDebug            # build APK; also compiles libnative-lib.so via CMake for 4 ABIs
sh gradlew installDebug             # install on a connected device/emulator
sh gradlew lint                     # Android lint
sh gradlew :app:externalNativeBuildDebug   # rebuild only the C++ solver
sh gradlew testDebugUnitTest --tests "com.example.v2_sudoku_acp_android.ExampleUnitTest"
sh gradlew connectedDebugAndroidTest       # instrumented tests, needs a device
```

Testing caveat: only the template `ExampleUnitTest` / `ExampleInstrumentedTest` exist, and `app/build.gradle.kts` declares no `testImplementation` / `androidTestImplementation` dependencies (JUnit and Espresso are in `gradle/libs.versions.toml` but unused). Add those before writing real tests.

Toolchain: AGP 8.7.3, Gradle 8.12, Java 11 source level (daemon JVM 21 via foojay), compileSdk/targetSdk 35, minSdk 24, C++17. Dependency versions live in `gradle/libs.versions.toml`; the `tensorflow-lite*` aliases actually resolve to `com.google.ai.edge.litert` artifacts.

## Architecture

### Activity flow

All hand-offs are a JPEG written to `getExternalFilesDir(null)` plus the intent extras `image_path` and `grid_size` (9/16/25).

- **MainActivity** — launcher. Starts `CameraActivity` (camera) or `ProcessingActivity` (gallery pick, passed as a content URI) for a result, upscales the returned image by grid size (2x / 3.2x / 4x), and applies the tunable preview pipeline in `updateImageThreshold()` (normalize, optional median blur, bilateral filter, unsharp mask, optional morphological close, optional invert). Every slider change rewrites `captured_sudoku.jpg`, which is the file `SolverActivity` later reads. Also where `OpenCVLoader.initLocal()` is called.
- **CameraActivity** — CameraX `ImageAnalysis` loop. Adaptive-thresholds each frame, takes the largest 4-corner contour as the grid, estimates grid size from the median width of child contours (majority vote over the last 20 frames, or a user lock), and keeps the latest 500x500 perspective-warped *binary* mat for capture.
- **ProcessingActivity** — gallery path. Detects candidate quadrilaterals, the user taps one, it is warped to 1000x1000, then the user picks the grid size and toggles filters. The grid lines are drawn onto the image that is returned.
- **EditImageActivity** + `PaintView` — manual paint/erase touch-up of `captured_sudoku.jpg`.
- **SolverActivity** — slices the image into `n x n` cells, runs OCR, shows an editable `GridLayout` of `EditText`s, and calls the native solver. Long-pressing a cell shows the 28x28 image that was fed to the model (the main OCR debugging tool).
- `GalleryActivity` + `QuadrilateralSelectionView` (manual 4-corner selection) are registered in the manifest but nothing launches them; `ProcessingActivity` replaced that path.

### OCR (`SolverActivity.extractDigitForMnist` → `DigitRecognizer`)

Per cell: 5% inset, Gaussian blur, adaptive threshold (block 49, C 3), `clearBorderBand` zeroes a 6px frame to remove grid lines, the largest contour passing area/size/aspect filters is taken as the digit, scaled to fit 20px and centered on a 28x28 black canvas (MNIST layout, white digit on black). No valid contour means an empty cell.

`DigitRecognizer` loads the model named by `MODEL_FILE` from `app/src/main/assets/` (several `.tflite` iterations are kept there; only that constant decides which one is used), feeds 28x28 float32 in [0,1], expects a 10-class output, and returns -1 below `CONFIDENCE_THRESHOLD`, which the UI renders as a red `NA`.

The threshold constants here have been tuned by hand across several commits (Otsu was tried and reverted because of ghost digits); the commented-out blocks are that history.

### Native solver (`app/src/main/cpp/`)

`CMakeLists.txt` builds `native-lib` from `native-lib.cpp` plus everything globbed from `src/*.cpp` except `solvermain.cpp` (the desktop CLI `main`). New files dropped into `src/` are compiled automatically.

`native-lib.cpp` exposes one function, `SolverActivity.solveSudokuNative(String puzzle, int algorithm, int threads)`. The JNI symbol name encodes the Java package and class, so renaming either breaks linking at runtime. It builds a `Board`, picks a `SudokuSolver` implementation, and runs it with a hardcoded 10 s timeout and hardcoded ACS parameters:

| `algorithm` | Class | Notes |
|---|---|---|
| 0 | `SudokuAntSystem` | single-colony ACS |
| 1 | `BacktrackSearch` | exact baseline |
| 2 | `ParallelSudokuAntSystem` | MCAS, one colony per thread, with inter-colony communication and SA; what the app uses (4 threads) |

**Puzzle string contract** (`Board::Board(const string&)` in `src/board.cpp`): one character per cell, row-major, `.` for empty; the string length selects the order (81 / 256 / 625). The alphabet depends on size and is shared by input and returned solution:

- 9x9: `1`-`9`
- 16x16: `0`-`9` then `a`-`f`, where `0` means value 1
- 25x25: `a`-`y`

`SolverActivity.getBoardString()` concatenates the raw cell text, so anything that is not exactly one valid character per cell (an `NA` cell, a two-digit entry) changes the length and the board is rejected. The digit model only outputs 0-9, so 16x16 and 25x25 OCR does not yet produce this alphabet. An empty return string means failure or timeout.

The solve call is made synchronously on the UI thread.

`Board` holds a `ValueSet` (bitset of candidates) per cell; `SetCell` propagates constraints, which is the "constraint propagation" part of MCAS that both ACS variants rely on.

### Vendored research repo

The rest of `app/src/main/cpp/` is the upstream MCAS research repository copied in wholesale and is not part of the Android build: `scripts/run_general.py` (batch experiment runner), `webapp/` (Flask) and `webapp_improved/` (Vite + React), `vs2017/` (Visual Studio project with checked-in Windows binaries), `instances/` (about 2,700 puzzle files) and `results/` (experiment CSVs). `app/src/main/cpp/README.md` documents how to run those on Windows. Exclude `instances/` and `results/` when searching the tree.

## Existing docs

- `app/src/main/cpp/SOLVER_INTEGRATION_README.md` — JNI integration notes; accurate.
- `IMAGE_PROCESSING_DOCUMENTATION.md` — partly stale: it describes a Java backtracking `SudokuSolver`, a `PolygonSelectionView`, and OpenCV 4.13, none of which match the code (solver is native MCAS, OpenCV is 4.10.0). Trust the code over it.
