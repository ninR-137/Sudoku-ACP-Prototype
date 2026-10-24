# Sudoku ACP Solver — Project Documentation

Android prototype that reads a Sudoku puzzle from a photo, recognizes the clues with an on-device neural network, and solves it with the MCAS solver (Multithreaded Ant Colony System with constraint propagation and simulated annealing), written in C++ and called from Java.

**Current status**

| Capability | 9x9 | 16x16 | 25x25 |
|---|---|---|---|
| Scan and read from a photo | Working | Not yet (two-digit values cannot be read) | Not yet |
| Solve a board entered or corrected by hand | Working | Working | Supported, not yet tested |

---

## Contents

1. [Technology stack](#1-technology-stack)
2. [Project structure](#2-project-structure)
3. [User guide](#3-user-guide)
4. [How the app works](#4-how-the-app-works)
5. [Development and testing](#5-development-and-testing)
6. [Known limitations](#6-known-limitations)
7. [Changes so far and action points](#7-changes-so-far-and-action-points)

---

## 1. Technology stack

| Component | Version | Used for |
|---|---|---|
| Java | 11 (source level) | All app code |
| Android SDK | compileSdk / targetSdk 35, minSdk 24 (Android 7.0) | Platform |
| OpenCV (Android SDK, Java bindings) | 4.10.0 | Board detection, perspective correction, filtering, thresholding, contours, morphology |
| CameraX | 1.4.1 | Live camera preview and frame analysis |
| LiteRT (TensorFlow Lite) | 2.1.6 (support/metadata 1.4.2) | On-device digit classification |
| Android NDK + CMake | CMake 3.22.1, C++17 | Compiling the C++ solver into a native library |
| JNI (Java Native Interface) | — | Calling the C++ solver from Java |
| Build | Android Gradle Plugin 8.7.3, Gradle 8.12 | Build system |
| Testing | JUnit 4, AndroidX Test, Espresso | Instrumented tests on a device or emulator |

Dependency versions are defined in `gradle/libs.versions.toml`.

---

## 2. Project structure

Only the files that make up the app are listed.

| Path | Purpose |
|---|---|
| `app/src/main/java/com/example/v2_sudoku_acp_android/MainActivity.java` | Home screen: starts camera or gallery input, previews and enhances the board image, opens the solver |
| `.../CameraActivity.java` | Live camera scanning with automatic board and grid-size detection |
| `.../ProcessingActivity.java` | Gallery input: board selection, perspective correction, grid size, optional filters |
| `.../EditImageActivity.java`, `PaintView.java` | Manual paint/erase editor for the board image |
| `.../SudokuOcr.java` | Cuts the board into cells, decides which cells contain a digit, prepares digits for the model |
| `.../DigitRecognizer.java` | Loads the TFLite model and classifies one digit image |
| `.../SolverActivity.java` | Editable grid, board validation, calls the native solver, test-data export |
| `app/src/main/assets/*.tflite` | Digit recognition models (only the one named in `DigitRecognizer.MODEL_FILE` is used) |
| `app/src/main/cpp/native-lib.cpp` | JNI bridge between Java and the C++ solver |
| `app/src/main/cpp/CMakeLists.txt` | Native build recipe |
| `app/src/main/cpp/src/` | MCAS solver source (C++) |
| `app/src/androidTest/java/.../OcrAccuracyTest.java` | Measures recognition accuracy over labelled images |
| `app/src/androidTest/java/.../SolverActivityTest.java` | Tests validation and solving on the solver screen |
| `app/src/androidTest/assets/ocr_fixtures/` | Labelled test images for `OcrAccuracyTest` |

The rest of `app/src/main/cpp/` (Flask and React web apps, Python batch scripts, Visual Studio project, puzzle instances, experiment results) is the original MCAS research repository, kept for reference. It is not part of the Android build.

`GalleryActivity.java` and `QuadrilateralSelectionView.java` are an older manual corner-selection screen. They are still registered in the manifest but nothing opens them.

---

## 3. User guide

### 3.1 Permissions

On first launch the app asks for camera access. It is only needed for the **Camera** option; the **Gallery** option uses Android's photo picker and needs no permission.

### 3.2 Overview of the flow

```
Home screen ──Camera──▶ Camera screen ──Capture──┐
            └─Gallery─▶ Photo picker ─▶ Board selection screen ──Finish──┤
                                                                          ▼
                                       Home screen (preview + adjustments)
                                                                          │ Process Grid
                                                                          ▼
                                                             Solver screen ──Solve with MCAS──▶ Solution
```

### 3.3 Option A: Scan with the camera

1. On the home screen, tap **Camera**.
2. Point the camera at the puzzle. The screen shows the processed (black-and-white) view, with the detected board outlined in green and the detected size at the top (for example "9x9 Grid").
3. If the size is wrong or keeps changing, choose it yourself: **9x9**, **16x16** or **25x25** locks the size; **Auto** detects it.
4. If the outline does not appear, adjust the **Threshold** slider (it controls how dark a line must be to count as part of the grid).
5. Tap **Capture** once the outline matches the puzzle. You return to the home screen with the board shown.

### 3.4 Option B: Load a photo from the gallery

1. On the home screen, tap **Gallery** and pick a photo.
2. The app highlights every rectangle it finds, labelled **#1**, **#2** and so on. Tap the one that is the puzzle. If the puzzle is not highlighted, the photo needs a clearer, fully visible border.
3. The board is flattened into a square. Choose the grid size: **9x9**, **16x16** or **25x25**. A grid overlay shows where cells will be cut; it should line up with the printed grid.
4. Optional filters, each toggled on and off, some with a slider:
   - **Original**: removes all filters.
   - **Gray**: converts to grayscale.
   - **Thresh**: black-and-white conversion (slider: sensitivity).
   - **Canny**: edge detection (sliders: two edge thresholds).
   - **Blur**: Gaussian blur (slider: strength).
   - **Median**: removes salt-and-pepper noise (slider: strength).
   - **Heal Grid**: closes small gaps in lines and strokes (slider: size).

   For most photos, no filter gives the best result.
5. Tap **Finish**. You return to the home screen with the board shown.

### 3.5 Adjust the image on the home screen

These are optional; the defaults suit most images.

- **Rotate Left / Rotate Right**: turns the board 90°. Use it if the photo was sideways.
- **Crop**: opens the phone's crop tool, if the phone has one.
- **Median Blur (Denoise)** switch and slider: removes speckle noise.
- **Heal Grid (Morphology)** switch and slider: thickens and joins broken strokes.
- **Invert Colors**: swaps black and white.
- **Edit image** (pencil icon on the preview): opens the image editor:
  - toggle between **paint** and **zoom/pan** mode;
  - switch the brush between **black** (draw) and **white** (erase marks, stains or stray lines);
  - set the brush size, **undo**, and **save** to return.

When the preview looks clean, tap **Process Grid**.

### 3.6 Review and solve

The solver screen shows the recognized grid. Shaded and white blocks mark the boxes.

| What you see | Meaning | What to do |
|---|---|---|
| Blue number | Digit recognized | Check it against the puzzle |
| Empty cell | No digit detected | Type the digit if the puzzle has one there |
| Red **N** (9x9) or **NA** (larger grids) | A mark was found but could not be read confidently | Replace it with the correct digit, or clear it |
| Red number | The same value appears twice in a row, column or box; one of them is wrong | Find and fix the wrong one |

- Tap a cell to edit it. Values are plain numbers from 1 to the grid size (1–9, 1–16 or 1–25).
- **Long-press a cell** to see the exact 28x28 image the recognizer received for it, with an option to save it to the gallery. Useful for understanding a misread.
- Tap **Solve with MCAS**:
  - If the board has a problem, the affected cells turn red and a message explains it ("cell(s) are not a number from 1 to n" or "A value repeats in a row, column or box"). Nothing is solved until it is fixed.
  - Otherwise the button changes to **Solving...** and the grid is locked. The solver runs for at most 10 seconds.
  - On success, the solution fills the grid and "Solved!" appears. If no solution is found in time, "No solution found within timeout" appears. This also happens if a clue is wrong but causes no visible conflict, so re-check the clues.

### 3.7 Saving test data (for evaluation)

Long-press **Solve with MCAS** **before** solving to save the current image and the grid as shown as a labelled test case. Correct every wrong cell first, because the saved grid is used as the answer key. See [5.4](#54-adding-test-images).

---

## 4. How the app works

### 4.1 Screen flow and data passing

Screens pass the board image to each other as a JPEG file in the app's private external storage (`getExternalFilesDir`), together with two values in the screen-opening intent: `image_path` and `grid_size` (9, 16 or 25). The file the solver screen reads is `captured_sudoku.jpg`.

### 4.2 Image acquisition

**Camera path (`CameraActivity`)**

1. CameraX delivers preview frames for analysis (640x480 by default); only the brightness (grayscale) channel is used.
2. Each frame is converted to black and white with Gaussian adaptive thresholding. The threshold slider sets the threshold offset.
3. Contours are found. The largest contour (area over 5,000 px) that simplifies to four corners is taken as the board.
4. **Grid-size estimation:** the contours directly inside the board are treated as cells. The board width divided by the median cell width gives an estimate, which is rounded to the nearest of 9, 16 or 25 (rejected if more than 4 away). The size is only shown once the same value wins a majority of the last 20 frames. A manual lock overrides this.
5. On **Capture**, the black-and-white board is perspective-warped to 500x500 px and saved.

**Gallery path (`ProcessingActivity`)**

1. The full-resolution photo is loaded.
2. Board candidates are found with Gaussian blur, adaptive thresholding and contour detection: every contour over 10,000 px that simplifies to four corners is highlighted.
3. The tapped candidate's corners are sorted (top-left, top-right, bottom-right, bottom-left) and the board is perspective-warped to 1000x1000 px.
4. Optional filters (section 3.4) are applied in a fixed order: grayscale, median, threshold, heal (morphological closing), Canny, blur.
5. On **Finish**, the grid overlay is drawn onto the image, which is converted to grayscale and saved.

### 4.3 Enhancement (`MainActivity`)

1. The returned image is upscaled with cubic interpolation by 2x (9x9), 3.2x (16x16) or 4x (25x25).
2. Each time a control changes, the pipeline runs from the upscaled image:
   1. contrast normalization to the full 0–255 range;
   2. optional median blur;
   3. bilateral filter (diameter 9): smooths paper texture while keeping edges sharp;
   4. unsharp masking (original x 1.5 minus Gaussian-blurred x 0.5): sharpens digit strokes;
   5. optional morphological closing;
   6. optional inversion.
3. The result is shown in the preview and saved as `captured_sudoku.jpg`.

### 4.4 Digit extraction (`SudokuOcr`)

`SudokuOcr.readBoard(image, n)` has no user-interface code, so it can be run from tests. For each of the n x n cells:

1. **Cut the cell** on an even grid, with a 5% inset on every side to stay clear of the grid lines.
2. **Binarize:** Gaussian blur (3x3), then Gaussian adaptive thresholding (block size 49, offset 3), so ink becomes white on black.
3. **Remove grid lines at the edge:** a 6 px frame around the cell is blanked (`BORDER_BAND`).
4. **Remove specks:** morphological opening with a 3x3 kernel deletes isolated pixels from paper grain.
5. **Find the digit candidate:** external contours are found. The largest contour passing all of these filters is kept:

   | Filter | Rule | Purpose |
   |---|---|---|
   | Area | 0.5% to 70% of the cell | Rejects specks and fully filled cells |
   | Width | at least 4% of cell width | Rejects slivers |
   | Height | at least 25% of cell height (`MIN_DIGIT_HEIGHT_RATIO`) | Rejects texture blobs; small handwritten digits still pass |
   | Aspect ratio | width / height between 0.1 and 1.5 | Rejects horizontal lines and wide smudges |
   | Grid-line test | rejects a blob that starts at the blanked frame and runs at least 90% of that edge (`GRID_LINE_SPAN_RATIO`) | Rejects grid lines that leak past the frame on curved pages |

6. **Contrast check:** the average gray level inside the candidate is compared with the average of the surrounding paper. If the difference is below 40 (`MIN_INK_CONTRAST`), the cell is treated as empty. Real ink is far darker than paper; texture and show-through print are not.
7. **Normalize:** the candidate is scaled so its longer side is 20 px and centered on a 28x28 black image (white digit on black, the MNIST layout).

A cell with no surviving candidate is **empty**. Rejected candidates are logged under the `SudokuOcr` tag with the reason (size, ratios, position), and accepted ones with their contrast, for tuning.

### 4.5 Digit recognition (`DigitRecognizer`)

- Loads the model named in `MODEL_FILE` from `app/src/main/assets/`, currently `tflite_model_Translation0.05_Rotation0.05_Zoom0.05_Patience8_LR0.001.tflite`. Runs on the CPU with 2 threads.
- Input: one 28x28 image as 784 float values scaled to 0–1. Output: 10 scores, one per digit 0–9.
- The highest score wins. If it is below 0.30 (`CONFIDENCE_THRESHOLD`), the cell is marked **unreadable**. On a 9x9 board a prediction of 0 is also marked unreadable, since 0 is not a valid value.
- **Model:** a small convolutional neural network. The exported file contains three convolution layers (each followed by batch normalization and ReLU), two max-pooling layers, and two dense layers. Training is done outside this repository: according to the training notes, on MNIST (handwritten digits) combined with TMNIST (typeface digits), using the Adam optimizer, early stopping, and random translation, rotation and zoom (0.05 each) as data augmentation, then exported to TensorFlow Lite with quantization.

### 4.6 Review, validation and encoding (`SolverActivity`)

1. Results are written into the grid: digits in blue, unreadable cells as red N/NA. Any value repeated in a row, column or box turns red.
2. On **Solve**, `buildPuzzleString()` checks that the grid size is 9, 16 or 25, that every cell is empty or a whole number from 1 to n, and that no value repeats. Problem cells are marked red, and the solver is not called.
3. The board is encoded into the solver's format: one character per cell, row by row, `.` for empty. The alphabet depends on size:

   | Grid | Values 1..n are written as |
   |---|---|
   | 9x9 | `1`–`9` |
   | 16x16 | `0`–`9`, then `a`–`f` (so `0` = 1 and `f` = 16) |
   | 25x25 | `a`–`y` |

   The returned solution is decoded back to numbers the same way (`toSolverChar` / `fromSolverChar`).

### 4.7 Native solver integration (JNI)

1. **Source:** the MCAS C++ files were copied into `app/src/main/cpp/src/`. `std::random_shuffle` (removed in newer C++) was replaced with `std::shuffle` so the code compiles with the Android compiler.
2. **Bridge:** `native-lib.cpp` defines `Java_com_example_v2_1sudoku_1acp_1android_SolverActivity_solveSudokuNative`. The name encodes the Java package, class and method, so renaming any of them breaks the link. It converts the Java string to a C++ `Board`, runs the chosen solver with a 10-second limit, and returns the solution as a Java string (empty on failure).
3. **Build recipe:** `CMakeLists.txt` compiles the bridge and every `.cpp` in `src/`, except the desktop entry point `solvermain.cpp`, into the shared library `libnative-lib.so`, linked with Android's log library.
4. **Gradle:** `app/build.gradle.kts` points `externalNativeBuild` at the CMake file, sets C++17, and builds for four processor types: arm64-v8a, armeabi-v7a, x86_64 and x86. The NDK compiles one library per type, and all are packed into the APK; the phone loads the one matching its processor.
5. **Java side:** `SolverActivity` loads the library with `System.loadLibrary("native-lib")` and declares `public native String solveSudokuNative(String puzzle, int algorithm, int threads)`.
6. **Threading:** the call runs on a single background thread, so the screen stays responsive. The result is posted back to the main thread and ignored if the screen has been closed meanwhile. The native call itself cannot be cancelled.

Algorithm choices in the bridge: `0` = single-colony ACS, `1` = backtracking, `2` = MCAS. The app always uses MCAS with 4 colonies.

### 4.8 The MCAS solver

**Board representation and constraint propagation (`board.cpp`, `valueset.h`).** Each cell stores its remaining candidate values as bits in a 64-bit integer. Setting a cell re-checks every cell in the same row, column and box, applying two rules: values already placed in the unit are removed from the cell's candidates (if one remains, it is placed), and a candidate that fits nowhere else in its unit is placed. Placements trigger further propagation, so the clues alone often fill much of the board.

**Ant (`sudokuant.cpp`).** An ant copies the puzzle, starts at a random cell and visits every cell once. For each undecided cell it chooses a candidate: with probability `q0` the one with the most pheromone, otherwise by pheromone-weighted random choice. It places it (with propagation) and slightly lowers that pheromone (local update) so other ants explore alternatives. Cells left with no candidates count as failures. An ant's score is the number of cells it filled.

**Colony iteration (`SubColony`).** 25 ants each build a board; the best becomes the iteration best and, if it beats the record, the colony's best-so-far. The reward for a board is `cells / (cells − filled)`, which rises steeply as the board nears completion. Pheromone on the best-so-far board's values moves toward the reward. The reward benchmark decays each iteration (`bestEvap`), so a stagnant record can be replaced.

**Parallel colonies (`ParallelSudokuAntSystem`).** Four colonies run on separate threads, each with its own pheromone table and random seed. Every 60 iterations (every 25 after iteration 100) they meet at a barrier and exchange boards:
- **Ring:** each colony sends its iteration best to the next colony.
- **Random matching:** colonies are shuffled, and each receives the best-so-far of its neighbour in that order.

Each colony then reinforces pheromone from three boards: its own iteration best, the ring board and the random-partner board.

**Simulated annealing (`simulatedannealing.cpp`).** Every 25 iterations each colony repairs its best-so-far:
1. Empty cells in each box are filled with the values missing from that box.
2. Cells in row or column conflicts are swapped, accepting worse swaps with probability `exp(−increase / temperature)`. The temperature starts at 5.75 and is multiplied by 0.995 per step down to 0.01.
3. Cells still in conflict are cleared.

The repaired board is accepted if it fills more cells, or fills at most 2 fewer while removing at least 5 conflicts.

**Stopping.** The solve ends when any colony has a complete board, when annealing produces a complete conflict-free board, or after 10 seconds. The most complete board across the colonies is returned; the solve counts as successful if every cell is filled.

**Parameters used by the app** (set in `native-lib.cpp`):

| Parameter | Value |
|---|---|
| Colonies (threads) | 4 |
| Ants per colony | 25 |
| `q0` (greedy choice probability) | 0.7 |
| `rho` (global pheromone update rate) | 0.7 |
| `pher0` (initial pheromone) | 1 / number of cells |
| `bestEvap` (reward decay) | 0.0075 |
| `xi` (local pheromone decay) | 0.5 |
| Simulated annealing frequency | every 25 iterations |
| Exchange interval | 60, then 25 after iteration 100 |
| Time limit | 10 s |

---

## 5. Development and testing

### 5.1 Requirements

Android Studio with the Android SDK, NDK and CMake 3.22.1 (SDK Manager > SDK Tools). `local.properties` must point `sdk.dir` at the SDK.

### 5.2 Build and run

The simplest way is **Run** in Android Studio. From a terminal (`gradlew` is not marked executable, so run it through `sh`):

```bash
sh gradlew installDebug -Pandroid.injected.build.abi=arm64-v8a
```

`-Pandroid.injected.build.abi=arm64-v8a` builds only for the emulator's or phone's processor. Without it the APK contains all four processor types and is about 180 MB, mostly OpenCV.

To check that only the C++ compiles:

```bash
sh gradlew :app:externalNativeBuildDebug
```

### 5.3 Tests

Both tests are instrumented, meaning they run on a device or emulator, because OpenCV, TensorFlow Lite and the native solver need Android.

- **`OcrAccuracyTest`** runs `SudokuOcr` on every image in `app/src/androidTest/assets/ocr_fixtures/` and compares the result with the answer key. It reports, per image and in total, the number of correct cells and every error, classified as false positive (empty cell read as a digit), missed (digit read as empty), misread (wrong digit) or unreadable. It fails only if total accuracy is below `MIN_ACCURACY`, currently 0.0.
- **`SolverActivityTest`** opens the solver screen without an image, types in a 9x9 and a 16x16 puzzle (taken from the MCAS instance files), solves them and checks that the result is a valid solution that keeps the clues. It also checks that boards with a repeated value, an unreadable cell or an out-of-range value are rejected without solving.

Run from Android Studio: open the test file and click the green arrow next to the class. The accuracy report appears in **Logcat** under the tag `OcrAccuracy`.

Run from a terminal:

```bash
sh gradlew installDebug installDebugAndroidTest -Pandroid.injected.build.abi=arm64-v8a
```

```bash
~/Library/Android/sdk/platform-tools/adb shell am instrument -w com.example.v2_sudoku_acp_android.test/androidx.test.runner.AndroidJUnitRunner
```

```bash
~/Library/Android/sdk/platform-tools/adb logcat -d -s OcrAccuracy:I
```

Add `-e class com.example.v2_sudoku_acp_android.OcrAccuracyTest` after `-w` to run a single test class. Avoid `connectedDebugAndroidTest`, which uninstalls the app (and any unexported test data) when it finishes.

### 5.4 Adding test images

A test case is the board image exactly as the solver screen receives it, plus a `.txt` file of the same name with the correct grid: one row per line, values separated by spaces, `.` for empty.

```
. 9 . . 5 4 2 7 6
7 . . 8 . 6 5 . .
```

1. Scan a puzzle in the app. Leave the home-screen adjustments at their defaults, so the test measures the app rather than manual tuning.
2. Correct every wrong cell on the solver screen.
3. Long-press **Solve with MCAS** before solving. A message confirms the saved file name.
4. Copy the files into the project:

```bash
~/Library/Android/sdk/platform-tools/adb pull /sdcard/Android/data/com.example.v2_sudoku_acp_android/files/ocr_fixtures app/src/androidTest/assets/
```

Rename each pair with a prefix describing the condition (for example `clean_`, `textured_`, `handwritten_`, `bowed_`, `lowlight_`), keeping both files' names identical. Check the `.txt` against the photo once, since it is the answer key.

To build variety quickly, photograph the same puzzle under several conditions and reuse one carefully checked `.txt` for all of them.

---

## 6. Known limitations

- **Large grids from photos:** the model reads one digit (0–9) per cell, so the values 10–25 on 16x16 and 25x25 boards cannot be recognized and must be typed in.
- **Camera path image quality:** the capture is a 640x480 preview frame, saved already converted to black and white, at 500x500 px. Detail is limited, especially for larger grids, and the OCR contrast check cannot help because no gray levels remain.
- **Gallery path resolution:** boards are warped to 1000x1000 px with linear interpolation, about 111 px per cell on 9x9 but only 40 px on 25x25. The later upscaling adds no detail.
- **Even-grid cell cutting:** cells are cut on an evenly spaced grid, which assumes a flat page. Curved pages can push grid lines into cells; the grid-line test handles most but not all cases.
- **Repeated compression:** images are saved as JPEG between steps; editing or cropping applies the enhancement filters again on top of an already filtered image.
- **Screen rotation** on the solver screen re-runs recognition and discards manual corrections.
- **Solver result check:** success is decided by counting filled cells; the existing `Board::CheckSolution` is not called before returning. Simulated annealing returns its current state rather than the best state it found.
- **Accuracy gate:** `MIN_ACCURACY` is 0.0, so `OcrAccuracyTest` reports but never fails.

---

## 7. Changes so far and action points

### 7.1 Changes so far

**Earlier work**
- Image pipeline with camera and gallery input, board detection, perspective correction, enhancement controls and a manual image editor.
- Several digit-recognition models trained and compared; the current model uses augmentation (translation, rotation, zoom). Outdated models were removed from the assets.
- Adaptive thresholding chosen over Otsu thresholding after Otsu produced ghost digits.
- C++ MCAS solver integrated through JNI and CMake.

**Recognition improvements**
- **False digits in empty cells fixed.** On textured or newspaper paper, paper grain was thresholded into blobs and classified as digits. Added a contrast check (ink must be at least 40 gray levels darker than the paper), a minimum blob height (25% of the cell) and speck removal (3x3 morphological opening).
- **Small handwritten digits restored.** The first height limit (35%) emptied a small handwritten 5; it was lowered to 25%, and rejected blobs are now logged with the reason.
- **Leaked grid lines rejected.** On a curved page, the cell border was read as a 1. Blobs that start at the cell edge and run along it are now treated as grid lines.
- **Conflict highlighting.** Values repeated in a row, column or box are shown in red, so misreads are easy to spot.

**Testing infrastructure**
- Recognition code moved from `SolverActivity` into the standalone `SudokuOcr` class.
- `OcrAccuracyTest` added, with a fixture export (long-press Solve) for building labelled test data. The test set now holds 7 labelled 9x9 images.
- `SolverActivityTest` added.
- Test dependencies (JUnit, AndroidX Test, Espresso) added to the build.

**Solving**
- **Board validation before solving.** Invalid values and repeats are marked and the solver is not called. Previously, invalid input reached the C++ code and failed silently.
- **Solve moved off the main thread.** Previously the screen froze for up to 10 seconds, long enough to trigger Android's "app not responding" dialog.
- **Large-grid values supported.** The grid now uses plain numbers 1 to n and converts them to the solver's alphabet. Previously, typing a two-digit value such as 12 broke the board.
- **Export bug fixed.** On 9x9 boards an unreadable cell shows "N" (cells hold one character), which the fixture export did not recognize; it now uses the same validation as Solve.

**Environment and documentation**
- Emulator storage increased to 12 GB; arm64-only builds documented.
- `CLAUDE.md` and this document added.

### 7.2 Action points

**Immediate**
1. Commit the pending changes in the working tree (`CLAUDE.md` and the staged removal of one fixture).
2. Run `OcrAccuracyTest` on the 7 fixtures, record the per-image results, and rename the fixtures by condition.
3. Set `MIN_ACCURACY` just below the current 9x9 accuracy so later changes that break a working image fail the test.

**Planned features**
4. **Multiple input methods:** add a way to open the solver screen without scanning, to enter a puzzle manually, and to load a puzzle from a text file. Supporting the MCAS instance format (`instances/`) would allow loading the existing puzzle library.
5. **Lighting robustness:** collect fixtures under dim, uneven and shadowed lighting and measure accuracy per condition.
6. **Two-digit recognition for 16x16 and 25x25:** keep every accepted blob in a cell, classify side-by-side blobs separately and combine them (for example 1 and 6 into 16), discarding values above n.

**Quality improvements (measure each with `OcrAccuracyTest`)**
7. Camera path: capture a full-resolution photo and save it in grayscale rather than thresholded.
8. Gallery path: warp at a size based on the grid (about 100 px per cell) with area interpolation, drop the upscale, and save intermediate images as PNG.
9. Cut cells along detected grid lines rather than an even grid, if test results show edge errors on curved pages.
10. Call `Board::CheckSolution` before reporting success, and return the best state from simulated annealing.
11. Keep the board on screen rotation.

**Optional**
12. On-device benchmark of MCAS against backtracking on 9x9, 16x16 and 25x25 instances, to support the thesis claim that MCAS is practical on phones.
13. Remove the unused `GalleryActivity` and `QuadrilateralSelectionView`, and retire the outdated `IMAGE_PROCESSING_DOCUMENTATION.md`.
