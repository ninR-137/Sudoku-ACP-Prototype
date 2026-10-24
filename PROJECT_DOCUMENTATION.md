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
| CameraX | 1.4.1 | Kept for the disabled in-app camera screen (`CameraActivity`); photos are now taken with the phone's camera app |
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
| `.../CameraActivity.java` | **Disabled (commented out).** Previous in-app camera with live board detection, kept for reference; restore steps are at the top of the file |
| `.../ProcessingActivity.java` | Board selection, perspective correction, grid size and optional filters, for camera and gallery images |
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

On first launch the app asks for camera access. It is needed for the **Camera** option: Android only opens the camera app for an app holding this permission when the app declares it. The **Gallery** option uses Android's photo picker and needs no permission.

### 3.2 Overview of the flow

```
Home screen ──Camera──▶ Camera screen ──Capture──┐
            └─Gallery─▶ Photo picker ────────────┴─▶ Board selection screen ──Finish──┐
                                                                                     ▼
                                                  Home screen (preview + adjustments)
                                                                                     │ Process Grid
                                                                                     ▼
                                                        Solver screen ──Solve with MCAS──▶ Solution
```

### 3.3 Option A: Scan with the camera

1. On the home screen, tap **Camera**. The phone's own camera app opens.
2. Point the camera at the puzzle so the whole grid, including its outer border, is in view. Hold the phone as straight above the page as you can. The camera app's features, such as night mode, can be used as normal.
3. Take the photo and confirm it in the camera app (for example with a ✓ or **OK** button, depending on the phone).
4. The photo opens on the board selection screen. Continue from step 2 of Option B below.

### 3.4 Option B: Load a photo from the gallery

1. On the home screen, tap **Gallery** and pick a photo.
2. The app highlights every rectangle it finds, labelled **#1**, **#2** and so on (largest first). Tap inside the puzzle; if it sits inside another highlighted rectangle, such as the page, the smaller one (the puzzle) is selected. If the puzzle is not highlighted, the photo needs a clearer, fully visible border.
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

**Camera (phone's camera app)**

`MainActivity` opens the phone's own camera app with Android's standard image-capture request (`ActivityResultContracts.TakePicture`). The camera app saves the full-resolution photo directly to `camera_photo.jpg` in the app's storage, which it is given access to through the app's `FileProvider`. When the user confirms the photo, `MainActivity` opens `ProcessingActivity` with it, so camera and gallery images take exactly the same path from here on. The photo's orientation is stored in its EXIF data, which OpenCV applies when loading it.

The previous in-app camera screen (`CameraActivity`, built on CameraX, with live board and grid-size detection while aiming) is commented out but kept in the project. The steps to restore it are at the top of `CameraActivity.java`.

**Board selection (`ProcessingActivity`)**

1. The photo is loaded at full resolution (from a file path for camera photos, or a content URI for gallery picks). If its longest side exceeds 4032 px, it is shrunk to that size (about 12 MP): camera apps can save 50–200 MP photos, which are too large to display (Android refuses images over about 100 MB) or process quickly.
2. Board candidates are found on a copy reduced to 1000 px (`findBoardCandidates`). Shrinking averages out sensor grain, which matters in low light, and makes the result independent of photo size:
   1. if the image is very flat (its brightness range is under 100 gray levels, as in an underexposed photo), the range is stretched to 0–255, so faint lines stay darker than the threshold margin. Normal photos, including dim ones the camera has brightened (measured ranges 125–182), are left alone, because stretching them amplifies grain and shading and breaks the board outline;
   2. Gaussian adaptive thresholding with a neighbourhood that scales with the image (2% of its width) and an offset of 5, the same settings as the camera's live detection;
   3. morphological closing joins small gaps in the lines;
   4. contours are found including nested ones, so a board inside a page border, or one merged with background noise, is still found. Contours between 1.6% and 95% of the image (larger ones are the edge of the image itself) that simplify to a convex four-cornered shape become candidates;
   5. a candidate inside another candidate more than 8 times its size is dropped (the boxes and cells of a board), as is one inside another of nearly the same size (over 80%, the inner edge of the same thick border).

   The corners are scaled back to the full photo, and candidates are numbered largest first.
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
- **Board resolution:** on the board selection screen, boards are warped to 1000x1000 px with linear interpolation, about 111 px per cell on 9x9 but only 40 px on 25x25. The later upscaling adds no detail.
- **Even-grid cell cutting:** cells are cut on an evenly spaced grid, which assumes a flat page. Curved pages can push grid lines into cells; the grid-line test handles most but not all cases.
- **Repeated compression:** images are saved as JPEG between steps; editing or cropping applies the enhancement filters again on top of an already filtered image.
- **Screen rotation** on the solver screen re-runs recognition and discards manual corrections.
- **Solver result check:** success is decided by counting filled cells; the existing `Board::CheckSolution` is not called before returning. Simulated annealing returns its current state rather than the best state it found.
- **Accuracy gate:** `MIN_ACCURACY` is 0.0, so `OcrAccuracyTest` reports but never fails.

---

## 7. Changes so far and action points

### 7.1 Changes so far

Each change lists the problem it addresses, the cause where known, and what was changed. Changes are grouped by area, roughly in the order they were made. Where the reason was not recorded in the commit history, this is stated.

#### Earlier work (July–September 2026)

**Debug view for processed cells**
- *Problem:* when a digit was misread, there was no way to see what the model had actually received, so misreads could not be diagnosed.
- *Change:* long-pressing a cell on the solver screen shows the image passed to the model, with an option to save it to the gallery.
- *Result:* this became the main tool for tuning recognition.

**Hollow digits after thresholding: switch to Otsu**
- *Problem:* after black-and-white conversion, digits came out hollow (only their outlines remained), and the model could not read them.
- *Cause:* adaptive thresholding compares each pixel with the average of a small neighbourhood (15 px at the time). Inside a stroke thicker than that neighbourhood, the average is the stroke itself, so its middle is not marked as ink.
- *Change:* switched to Otsu thresholding, which picks one threshold for the whole cell.

**Ghost digits with Otsu: back to adaptive thresholding, with a larger neighbourhood**
- *Problem:* Otsu produced "ghost numbers": digits detected in cells that were empty.
- *Cause:* Otsu always splits a cell into two groups, even when the cell holds only paper, so shading and texture are marked as ink. A fallback for cells with too little ink was tried first.
- *Change:* returned to adaptive thresholding, but with a neighbourhood large enough to cover thick strokes (39 px, then tuned to 49 px). This avoids hollow digits without Otsu's ghosting.

**Border cleanup changed from flood fill to a fixed band**
- *Problem:* remnants of grid lines at the cell edges were being taken as digits.
- *Change:* the original cleanup erased everything connected to the cell edge (flood fill); it was replaced by blanking a fixed 6 px band around each cell.
- *Reason:* not recorded. A likely cause is that flood fill also erases any digit touching a grid line, which is common with handwriting.

**Digit model trained on printed as well as handwritten digits**
- *Problem:* the first model recognized printed (typeface) digits poorly.
- *Cause:* it was trained only on MNIST, which contains handwritten digits.
- *Change:* the model was retrained on MNIST combined with TMNIST (typeface digits), and later with data augmentation (small random translation, rotation and zoom) for robustness. A model with 0.99 accuracy replaced earlier versions, and outdated models were removed from the app.

**MCAS solver integrated through JNI**
- *Problem:* the app needed the thesis solver, MCAS, which is written in C++.
- *Change:* the solver source was added to the project, with a JNI bridge (`native-lib.cpp`) and a CMake build. `std::random_shuffle`, which newer C++ standards removed, was replaced with `std::shuffle` so the code compiles with Android's compiler.

**Native library built for all processor types**
- *Problem:* the native library did not match the device's processor type (x86 emulator versus arm64 phones), so the solver could not be loaded.
- *Change:* the build now compiles the library for all four Android processor types (arm64-v8a, armeabi-v7a, x86_64 and x86), with C++17.

**Other earlier changes** (reasons not recorded): an invert-colors option on the home screen, and several adjustments to the model's confidence threshold (now 0.30).

#### Recognition reliability (October 2026)

**False digits in empty cells**
- *Problem:* on textured or newspaper paper, empty cells were read as random digits.
- *Cause:* adaptive thresholding only asks whether a pixel is slightly darker than its surroundings (by 3 gray levels). In an empty cell, paper grain and print showing through from the other side pass that test. The size filter then accepted these blobs, and the model had to choose a digit for them.
- *Change:* three checks decide that a cell is empty before the model runs:
  - the blob must be clearly darker than the paper around it (at least 40 gray levels);
  - it must be at least 25% of the cell's height;
  - isolated specks are removed first with a 3x3 morphological opening.
- *Result:* the textured newspaper image went from many false digits to fully correct.

**Small handwritten digits treated as empty**
- *Problem:* after the fix above, a small, flat handwritten 5 was read as an empty cell.
- *Cause:* the first height limit (35% of the cell) was too strict for small handwriting.
- *Change:* lowered to 25%. Rejected blobs are now logged with their size and position, so a rejection can be explained.

**Grid lines read as digits on curved pages**
- *Problem:* on a photo of a bowed page, a cell's border was read as a 1.
- *Cause:* cells are cut on an even grid, which assumes a flat page. On a curved page the real grid lines fall inside the cell, past the blanked band, and pass every other check because they are real ink.
- *Change:* a blob that starts at the cell's edge and runs along most of it (90%) is treated as a grid line. A digit that merely touches the edge is still accepted.

**Conflict highlighting**
- *Problem:* a misread digit looked like any other, so errors were easy to miss before solving.
- *Change:* a value repeated in a row, column or box is shown in red. On 9x9 boards a predicted 0, which is never valid, is shown as unreadable.

#### Testing infrastructure

- *Problem:* each recognition fix was checked by eye on the one photo that exposed the problem. One fix broke a previously working image (the small handwritten 5), showing that fixes were trading one failure for another.
- *Change:*
  - recognition code moved out of the solver screen into a standalone class, `SudokuOcr`, so it can be tested;
  - `OcrAccuracyTest` measures cell-level accuracy over a set of labelled images and classifies every error;
  - long-pressing Solve exports the current image and corrected grid as a labelled test case;
  - `SolverActivityTest` checks that valid puzzles solve correctly and invalid ones are rejected;
  - the test libraries were added to the build.
- *Result:* changes can now be measured against a growing test set (7 labelled 9x9 images so far) rather than judged on one photo. These numbers can also be reported in the thesis.

#### Solving

**Board validation before solving**
- *Problem:* unreadable cells ("N"/"NA") and invalid entries were sent straight to the solver.
- *Cause:* the solver expects exactly one valid character per cell. Anything else changed the length of the puzzle string, and the C++ code rejected the board silently, showing only "No solution found".
- *Change:* pressing Solve first checks that every cell is empty or a number from 1 to the grid size, and that no value repeats. Problem cells turn red with an explanation, and the solver is not called.

**Solving moved off the main thread**
- *Problem:* the screen froze for up to 10 seconds while solving.
- *Cause:* the solver ran on Android's main (user interface) thread; Android shows an "app not responding" dialog after about 5 seconds.
- *Change:* the solver runs on a background thread. The button shows "Solving..." and the grid is locked until it finishes.

**Two-digit values on 16x16 and 25x25 boards**
- *Problem:* typing a value such as 12 on a 16x16 board broke the puzzle.
- *Cause:* the solver uses one character per cell (16x16: `0`–`9`, then `a`–`f`; 25x25: `a`–`y`), and the cell text was passed to it unchanged.
- *Change:* the grid now always uses plain numbers from 1 to n, and the app converts them to and from the solver's characters. Typed 16x16 boards now solve (tested), and 25x25 is supported.

**Test-data export saving invalid answer keys**
- *Problem:* the export could save an unreadable cell into the answer file, which would then crash the accuracy test.
- *Cause:* on 9x9 boards each cell holds one character, so an unreadable cell shows "N", not "NA", and the export only checked for "NA".
- *Change:* the export now uses the same validation as Solve.

#### Camera capture

**Full-resolution photo instead of a preview frame**
- *Problem:* camera scans gave noticeably worse input than gallery photos.
- *Cause:* Capture saved a 640x480 preview frame, already converted to black and white, at 500x500 px. Detail was limited, especially for larger grids, and with no gray levels left, the recognizer's contrast check could not work.
- *Change:* Capture now takes a full-resolution color photo (capped near 12 MP, so photos from 50–200 MP sensors don't run out of memory). The photo goes through the same board selection screen as gallery images, so both inputs reach the recognizer in the same form. On existing photos the new flattened boards were read 79–81/81 cells correctly.

**Live grid detection kept as a guide**
- *Problem:* the board outline and grid size shown while aiming are useful feedback, especially to judge low-light conditions, but the black-and-white view was hard to read.
- *Change:* the camera was briefly simplified to a plain camera. Live detection was then restored as a guide only: the outline is drawn on the normal (grayscale) camera view, capture is always allowed, and the detected grid size is preselected on the board selection screen.

**Phone's camera app instead of the in-app camera**
- *Problem:* low-light photos from the in-app camera were of lower quality than those from the phone's own camera app.
- *Cause:* phone makers tune their own camera apps (night mode, HDR, noise reduction, focus) far beyond what a third-party app gets through CameraX. The six low-light test photos, taken with the phone's camera app, were all detected correctly.
- *Change:* the **Camera** button now opens the phone's camera app, which saves the photo straight into the app's storage; it then goes through board selection like a gallery image. The CameraX screen is commented out, not deleted, so it can be restored. This loses the live outline and grid-size guide while aiming. Because camera apps can save very large photos (50–200 MP), photos are now shrunk on load to at most 4032 px on the longest side, for camera and gallery alike.

#### Board detection in low light

- *Problem:* in low light, the board selection screen failed to find the board, even when the camera's live detection found it.
- *Cause:* the selection screen applied settings suited to small images to the full 12 MP photo:
  - a fixed 11 px threshold neighbourhood, smaller than a grid line in a large photo;
  - a fixed margin smaller than the sensor grain of a dim photo;
  - no repair of broken lines;
  - only the outermost outlines checked, so a board connected to the page edge by grain or shadow was never seen.

  The live detection works on a small frame, which averages the grain away, and gets about 30 attempts per second.
- *Change:* the selection screen now detects on a copy reduced to 1000 px:
  - very flat (underexposed) images are contrast-stretched first, so faint lines survive;
  - the threshold neighbourhood scales with the image;
  - small gaps in lines are joined;
  - nested outlines are checked, with filtering that hides a board's boxes and cells and duplicate outlines of thick borders.

  The corners are mapped back to the full photo. A tap inside several outlines selects the smallest, which is the board rather than the page.
- *Result:*
  - **Real low-light photos:** on 6 dim photos taken with the phone's camera app, the board was found in all 6, against 0 with the old method.
  - **Simulated low light:** on 5 sample photos darkened to a quarter of their brightness with sensor grain added (at 12 MP), it was found in all 5, against 0 before.
  - **Normal light:** found in all 5 sample photos, including one the old method missed.
  - **Extreme darkness:** on the real photos darkened a second time, far darker than a real dim photo, it was found in 2 of 6.
- *Lesson:* a first version stretched the contrast of every image. It fixed the simulated photos but broke 5 of the 6 real ones, which is why the stretch now only applies to very flat images, and why changes should be checked on real photos and not only simulations.

#### Development environment

- **Emulator storage:** the app (about 180 MB with all processor types) no longer fit on the emulator's 6 GB of storage, so installs failed. Storage was raised to 12 GB, and builds for a single processor type (`-Pandroid.injected.build.abi=arm64-v8a`) are documented.
- **Wireless debugging:** the phone could not be reached on the original Wi-Fi network, most likely because the router blocks devices from talking to each other. Both devices were moved to a phone hotspot and paired with a pairing code.
- **Documentation:** `CLAUDE.md` (guidance for AI coding assistants) and this document were added.

### 7.2 Action points

**Immediate**
1. Test the new camera capture and board detection on the phone (upright and sideways photos, low light), then commit the changes.
2. Run `OcrAccuracyTest` on the 7 fixtures, record the per-image results, and rename the fixtures by condition.
3. Set `MIN_ACCURACY` just below the current 9x9 accuracy so later changes that break a working image fail the test.

**Planned features**
4. **Multiple input methods:** add a way to open the solver screen without scanning, to enter a puzzle manually, and to load a puzzle from a text file. Supporting the MCAS instance format (`instances/`) would allow loading the existing puzzle library.
5. **Lighting robustness:** collect fixtures under dim, uneven and shadowed lighting and measure accuracy per condition.
6. **Two-digit recognition for 16x16 and 25x25:** keep every accepted blob in a cell, classify side-by-side blobs separately and combine them (for example 1 and 6 into 16), discarding values above n.

**Quality improvements (measure each with `OcrAccuracyTest`)**
7. Board selection: warp at a size based on the grid (about 100 px per cell) with area interpolation, drop the upscale, and save intermediate images as PNG.
8. Cut cells along detected grid lines rather than an even grid, if test results show edge errors on curved pages.
9. Call `Board::CheckSolution` before reporting success, and return the best state from simulated annealing.
10. Keep the board on screen rotation.

**Optional**
11. On-device benchmark of MCAS against backtracking on 9x9, 16x16 and 25x25 instances, to support the thesis claim that MCAS is practical on phones.
12. Remove the unused `GalleryActivity` and `QuadrilateralSelectionView`, and retire the outdated `IMAGE_PROCESSING_DOCUMENTATION.md`.
