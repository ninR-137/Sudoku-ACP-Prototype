package com.example.v2_sudoku_acp_android;

import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Reads the values of a warped, square Sudoku image: cuts it into cells, isolates the digit in
// each cell and classifies it. Has no UI dependencies so it can be driven from tests.
public class SudokuOcr {
    private static final String TAG = "SudokuOcr";

    public static final int EMPTY = -1;
    public static final int UNREADABLE = -2;

    // Minimum grey-level gap between a blob and the surrounding paper for it to count as ink
    private static final double MIN_INK_CONTRAST = 40.0;
    // Minimum blob height as a fraction of the cell height
    private static final double MIN_DIGIT_HEIGHT_RATIO = 0.25;
    // Width of the frame blanked on each cell edge to hide grid lines
    private static final int BORDER_BAND = 6;
    // A blob that starts at the blanked frame and runs this far along that edge is a grid line
    private static final double GRID_LINE_SPAN_RATIO = 0.9;

    public static class Result {
        // Per cell, row-major: the recognized value, EMPTY or UNREADABLE
        public final int[] values;
        // Per cell: the 28x28 image given to the model, null for empty cells
        public final Bitmap[] cellImages;

        Result(int cellCount) {
            values = new int[cellCount];
            cellImages = new Bitmap[cellCount];
        }
    }

    private final DigitRecognizer digitRecognizer;

    public SudokuOcr(DigitRecognizer digitRecognizer) {
        this.digitRecognizer = digitRecognizer;
    }

    public Result readBoard(Mat grayBoard, int n) {
        Result result = new Result(n * n);

        Mat fullImage = new Mat();
        Core.normalize(grayBoard, fullImage, 0, 255, Core.NORM_MINMAX);

        int cellW = fullImage.cols() / n;
        int cellH = fullImage.rows() / n;
        int insetX = (int) (cellW * 0.05);
        int insetY = (int) (cellH * 0.05);

        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int index = r * n + c;

                Rect cellRoi = new Rect(
                        (c * cellW) + insetX,
                        (r * cellH) + insetY,
                        cellW - (2 * insetX),
                        cellH - (2 * insetY)
                );

                Mat cellMat = new Mat(fullImage, cellRoi);
                Mat preparedDigit = extractDigitForMnist(cellMat, index);

                if (preparedDigit != null) {
                    Bitmap digitBitmap = Bitmap.createBitmap(28, 28, Bitmap.Config.ARGB_8888);
                    Utils.matToBitmap(preparedDigit, digitBitmap);
                    result.cellImages[index] = digitBitmap;

                    int digit = digitRecognizer.recognize(digitBitmap, index);
                    // 0 is not a valid value on a 9x9 board
                    if (digit > -1 && !(n == 9 && digit == 0)) {
                        result.values[index] = digit;
                    } else {
                        result.values[index] = UNREADABLE;
                    }

                    preparedDigit.release();
                } else {
                    result.values[index] = EMPTY;
                }

                cellMat.release();
            }
        }

        fullImage.release();
        return result;
    }

    private Mat extractDigitForMnist(Mat cell, int index) {
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(cell, blurred, new Size(3, 3), 0);

        Mat thresh = new Mat();
        Imgproc.adaptiveThreshold(
                blurred,
                thresh,
                255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                49,
                3
        );

//        Imgproc.threshold(
//                blurred,
//                thresh,
//                0,
//                255,
//                Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU
//        );

        // --- Fix 2: fallback to adaptive threshold if Otsu found almost nothing ---
//        double totalPixels = thresh.rows() * thresh.cols();
//        double whiteRatio = Core.countNonZero(thresh) / totalPixels;
//        Log.d(TAG, "Otsu whiteRatio (" + whiteRatio +
//                ") for cell " + index);

//        if (whiteRatio < 0.015) {
//            Log.d(TAG, "Otsu produced too little foreground (" + whiteRatio +
//                    ") for cell " + index + ", falling back to adaptive threshold");
//
//            Imgproc.adaptiveThreshold(
//                    blurred,
//                    thresh,
//                    255,
//                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
//                    Imgproc.THRESH_BINARY_INV,
//                    15,
//                    3
//            );
//        }
        // ---------------------------------------------------------------------

        int beforeClear = Core.countNonZero(thresh);

//        Log.d(TAG,
//                "cell=" + index +
//                        " before clearBorders whitePixels=" + beforeClear);

        blurred.release();

//        clearBorders(thresh);
        clearBorderBand(thresh, BORDER_BAND);

        // Remove isolated specks left by paper texture
        Mat openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        Imgproc.morphologyEx(thresh, thresh, Imgproc.MORPH_OPEN, openKernel);
        openKernel.release();

        int afterClear = Core.countNonZero(thresh);

//        Log.d(TAG,
//                "cell=" + index +
//                        " after clearBorders whitePixels=" + afterClear);


        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);  //Need to check how this works

//        Log.d(TAG,
//                "cell=" + index +
//                        " contours=" + contours.size());


// Old Code
//        Rect bestRect = null;
//        double maxArea = 0;
//        double cellArea = cell.rows() * cell.cols();
//
//        for (MatOfPoint contour : contours) {
//            Rect rect = Imgproc.boundingRect(contour);
//            double area = Imgproc.contourArea(contour);
//            double aspectRatio = rect.height == 0 ? 0 : (double) rect.width / rect.height;
//
//            if (area > cellArea * 0.03 && area < cellArea * 0.80 && aspectRatio > 0.10 && aspectRatio < 1.5) {
//                if (area > maxArea) {
//                    maxArea = area;
//                    bestRect = rect;
//                }
//            }
//            contour.release();
//        }

//        // ---------------------------------------------------------------------
        Rect bestRect = null;
        double largestCandidateArea = 0;
        // Largest contour regardless of validity, only used to explain rejections in the log
        Rect largestRect = null;
        double largestArea = 0;
        double cellArea = cell.rows() * cell.cols();

        for (MatOfPoint contour : contours) {
//            Log.w(TAG, "Contour found in index: " + index);
            Rect rect = Imgproc.boundingRect(contour);
            double area = Imgproc.contourArea(contour);

            if (rect.height <= 0) {
                contour.release();
                continue;
            }

            if (area > largestArea) {
                largestArea = area;
                largestRect = rect;
            }

            double aspectRatio = (double) rect.width / rect.height;

            double minArea = cellArea * 0.005;
            double maxAllowedArea = cellArea * 0.70;

            int minWidth = Math.max(2, (int) (cell.cols() * 0.04));
            int minHeight = Math.max(4, (int) (cell.rows() * MIN_DIGIT_HEIGHT_RATIO));

            // Grid line leaking past the blanked frame (grid not perfectly aligned to the cell)
            int innerW = cell.cols() - 2 * BORDER_BAND;
            int innerH = cell.rows() - 2 * BORDER_BAND;
            boolean touchesLeftOrRight = rect.x <= BORDER_BAND + 1 ||
                    rect.x + rect.width >= cell.cols() - BORDER_BAND - 1;
            boolean touchesTopOrBottom = rect.y <= BORDER_BAND + 1 ||
                    rect.y + rect.height >= cell.rows() - BORDER_BAND - 1;
            boolean isGridLine =
                    (touchesLeftOrRight && rect.height >= innerH * GRID_LINE_SPAN_RATIO) ||
                            (touchesTopOrBottom && rect.width >= innerW * GRID_LINE_SPAN_RATIO);

            boolean validCandidate =
                    !isGridLine &&
                    area > minArea &&
                            area < maxAllowedArea &&
                            rect.width >= minWidth &&
                            rect.height >= minHeight &&
                            aspectRatio > 0.10 &&
                            aspectRatio < 1.5;

//            Log.d(TAG,
//                    "cell=" + index +
//                            " area=" + area +
//                            " rect=" + rect.width + "x" + rect.height +
//                            " aspect=" + aspectRatio +
//                            " valid=" + validCandidate);

            if (validCandidate && area > largestCandidateArea) {
                largestCandidateArea = area;
                bestRect = rect;
            }

            contour.release();
        }

        // ---------------------------------------------------------------------
        hierarchy.release();

        if (bestRect == null) {
            if (largestRect == null) {
                Log.w(TAG, "bestRect == null for cell: " + index + " (no contours)");
            } else {
                Log.w(TAG, "bestRect == null for cell: " + index +
                        " largest rejected blob=" + largestRect.width + "x" + largestRect.height +
                        " cell=" + cell.cols() + "x" + cell.rows() +
                        " heightRatio=" + String.format(Locale.US, "%.2f", (double) largestRect.height / cell.rows()) +
                        " areaRatio=" + String.format(Locale.US, "%.3f", largestArea / cellArea) +
                        " aspect=" + String.format(Locale.US, "%.2f", (double) largestRect.width / largestRect.height) +
                        " at=" + largestRect.x + "," + largestRect.y);
            }
            thresh.release();
            return null;
        }

        // Reject low-contrast blobs: real ink is far darker than the paper, texture is not
        Mat inkMask = Mat.zeros(thresh.size(), CvType.CV_8UC1);
        thresh.submat(bestRect).copyTo(inkMask.submat(bestRect));
        Mat paperMask = new Mat();
        Core.bitwise_not(thresh, paperMask);
        clearBorderBand(paperMask, BORDER_BAND);

        double inkLevel = Core.mean(cell, inkMask).val[0];
        double paperLevel = Core.mean(cell, paperMask).val[0];
        double contrast = paperLevel - inkLevel;
        inkMask.release();
        paperMask.release();

        Log.d(TAG, "cell=" + index +
                " contrast=" + String.format(Locale.US, "%.1f", contrast) +
                " heightRatio=" + String.format(Locale.US, "%.2f", (double) bestRect.height / cell.rows()));

        if (contrast < MIN_INK_CONTRAST) {
            thresh.release();
            return null;
        }

        Mat digit = new Mat(thresh, bestRect);
        Mat mnistCanvas = Mat.zeros(28, 28, CvType.CV_8UC1);

        double scale = 20.0 / Math.max(bestRect.width, bestRect.height);
        int scaledW = Math.max(1, (int) Math.round(digit.cols() * scale));
        int scaledH = Math.max(1, (int) Math.round(digit.rows() * scale));

        Mat scaledDigit = new Mat();
        Imgproc.resize(digit, scaledDigit, new Size(scaledW, scaledH), 0, 0, Imgproc.INTER_AREA);


        int xOffset = (28 - scaledW) / 2;
        int yOffset = (28 - scaledH) / 2;
        Rect targetRect = new Rect(xOffset, yOffset, scaledW, scaledH);
        scaledDigit.copyTo(mnistCanvas.submat(targetRect));

        digit.release();
        scaledDigit.release();
        thresh.release();

        return mnistCanvas;
    }

//    private void clearBorders(Mat binary) {
//        int w = binary.cols();
//        int h = binary.rows();
//        Mat mask = new Mat(h + 2, w + 2, CvType.CV_8UC1, new Scalar(0));
//        Scalar black = new Scalar(0);
//
//        for (int i = 0; i < w; i++) {
//            if (binary.get(0, i)[0] == 255) Imgproc.floodFill(binary, mask, new Point(i, 0), black);
//            if (binary.get(h - 1, i)[0] == 255) Imgproc.floodFill(binary, mask, new Point(i, h - 1), black);
//        }
//        for (int i = 0; i < h; i++) {
//            if (binary.get(i, 0)[0] == 255) Imgproc.floodFill(binary, mask, new Point(0, i), black);
//            if (binary.get(i, w - 1)[0] == 255) Imgproc.floodFill(binary, mask, new Point(w - 1, i), black);
//        }
//        mask.release();
//    }

    private void clearBorderBand(Mat binary, int borderSize) {
        int w = binary.cols();
        int h = binary.rows();

        // Top
        Imgproc.rectangle(
                binary,
                new Point(0, 0),
                new Point(w - 1, borderSize - 1),
                new Scalar(0),
                -1
        );
        // Bottom
        Imgproc.rectangle(
                binary,
                new Point(0, h - borderSize),
                new Point(w - 1, h - 1),
                new Scalar(0),
                -1
        );
        // Left
        Imgproc.rectangle(
                binary,
                new Point(0, 0),
                new Point(borderSize - 1, h - 1),
                new Scalar(0),
                -1
        );
        // Right
        Imgproc.rectangle(
                binary,
                new Point(w - borderSize, 0),
                new Point(w - 1, h - 1),
                new Scalar(0),
                -1
        );
    }
}
