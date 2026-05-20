# Sudoku ACP Android - Image Processing Documentation

This document details the implementation, design, and image processing pipeline for the Sudoku Solver application.

## 1. Project Overview
The application is designed to capture Sudoku puzzles (9x9, 16x16, or 25x25) via the camera or gallery, process the image to extract a clean grid, recognize the digits using Machine Learning (TFLite), and solve the puzzle using a backtracking algorithm.

## 2. Technical Stack
- **OpenCV (4.13.0):** Core library for image manipulation, contour detection, and perspective transformation.
- **TensorFlow Lite:** Used for digit recognition (OCR).
- **Android Activity Result API:** Modern handling of camera, gallery, and cross-activity data.
- **Custom UI Components:** `PolygonSelectionView` for manual grid refinement.

---

## 3. Image Processing Pipeline

### Stage A: Image Acquisition
- **Camera:** Real-time capture with automated grid detection.
- **Gallery:** Users pick an image, which then passes through a manual refinement stage.

### Stage B: Manual Refinement (EditImageActivity)
For gallery images or failed auto-detections, the user can manually define the grid:
1. **PolygonSelectionView:** A custom view allows users to drag four corners to map the exact boundaries of the Sudoku grid.
2. **Dynamic Thresholding:** A SeekBar allows users to preview the binarization in real-time to ensure digits are clearly separated from the background before proceeding.

### Stage C: Pre-processing & Extraction (MainActivity)
Once a grid is identified (manually or automatically), the following steps are applied:
1. **Perspective Warp:** The 4-corner coordinates are used to perform a `getPerspectiveTransform` and `warpPerspective`, resulting in a perfectly square, flattened 500x500 grayscale image of the grid.
2. **Contrast Normalization:** `Core.normalize` scales pixel values to the full 0-255 range.
3. **Bilateral Filtering:** `Imgproc.bilateralFilter` smooths paper texture while strictly preserving digit edges.
4. **Unsharp Masking (Sharpening):** A blurred version of the image is subtracted from the original to enhance the high-frequency details of the digits.
5. **Adaptive Thresholding:** `Imgproc.adaptiveThreshold` (Gaussian) converts the image to binary, handling uneven lighting across the paper.
6. **Morphological Closing:** `Imgproc.morphologyEx` with a small kernel fills tiny gaps within the digit strokes.

---

## 4. OCR and Grid Parsing (SolverActivity)

### Cell Segmentation
The warped 500x500 image is divided into `n x n` equal sections (where `n` is 9, 16, or 25).

### Digit Detection Logic
To avoid passing empty cells or noise to the neural network:
1. **Blob Analysis:** `Imgproc.findContours` is run on each cell.
2. **Filtering:** Contours are filtered based on:
   - **Area:** Must be > 5% and < 70% of the cell area.
   - **Aspect Ratio:** Must be between 0.2 and 1.2 (prevents vertical/horizontal grid lines from being flagged).
   - **Centering:** The centroid must be within the inner 80% of the cell.

### TFLite Inference
- If a valid digit blob is found, the thresholded cell is passed to `DigitRecognizer.java`.
- The model returns a prediction (0-9). For 16x16 and 25x25 grids, the model is extended to handle multi-digit or hexadecimal characters.

---

## 5. Solving Algorithm (SudokuSolver)
The app utilizes a classic **Backtracking Algorithm**:
- **Constraints:** Ensures values are unique in their row, column, and block (3x3 for 9x9, 4x4 for 16x16, 5x5 for 25x25).
- **Efficiency:** The solver is optimized to handle the larger 25x25 state spaces using efficient pruning.

---

## 6. Activity Responsibilities
- **MainActivity:** Entry point, permission handling, and the final pre-processing of the warped grid.
- **CameraActivity:** High-speed camera feed and initial "live" grid detection.
- **EditImageActivity:** Manual adjustment of corners and threshold.
- **SolverActivity:** Segmentation, OCR, and the UI for displaying the solved results.
- **PolygonSelectionView:** UI logic for the interactive 4-point selection tool.
