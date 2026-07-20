package com.example.v2_sudoku_acp_android;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class SolverActivity extends AppCompatActivity {
    private GridLayout sudokuGrid;
    private EditText[] cellArray;
    private Bitmap[] debugCellImages;
    private int n;
    private String imagePath;
    private DigitRecognizer digitRecognizer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_solver);

        n = getIntent().getIntExtra("grid_size", 9);
        imagePath = getIntent().getStringExtra("image_path");
        if (n <= 0) n = 9;

        digitRecognizer = new DigitRecognizer(this);

        sudokuGrid = findViewById(R.id.sudokuGrid);
        sudokuGrid.setRowCount(n);
        sudokuGrid.setColumnCount(n);

        cellArray = new EditText[n * n];
        debugCellImages = new Bitmap[n * n];
        createBoard();

        if (imagePath != null) {
            processFullBoard();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (digitRecognizer != null) {
            digitRecognizer.close();
        }
    }

    private void processFullBoard() {
        Mat fullImage = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_GRAYSCALE);
        if (fullImage.empty()) {
            Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show();
            return;
        }

        Core.normalize(fullImage, fullImage, 0, 255, Core.NORM_MINMAX);

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
                Mat preparedDigit = extractDigitForMnist(cellMat);

                if (preparedDigit != null) {
                    Bitmap digitBitmap = Bitmap.createBitmap(28, 28, Bitmap.Config.ARGB_8888);
                    Utils.matToBitmap(preparedDigit, digitBitmap);
                    debugCellImages[index] = digitBitmap.copy(Bitmap.Config.ARGB_8888, false);

                    int digit = digitRecognizer.recognize(digitBitmap);
                    if (digit > 0) {
                        cellArray[index].setText(String.valueOf(digit));
                        cellArray[index].setTextColor(Color.BLUE);
                    } else {
                        cellArray[index].setText("");
                    }

                    preparedDigit.release();
                } else {
                    debugCellImages[index] = null;
                    cellArray[index].setText("");
                }

                cellMat.release();
            }
        }

        fullImage.release();
        Toast.makeText(this, "Scan Complete", Toast.LENGTH_SHORT).show();
    }

    private Mat extractDigitForMnist(Mat cell) {
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(cell, blurred, new Size(3, 3), 0);

        Mat thresh = new Mat();
        Imgproc.adaptiveThreshold(
                blurred,
                thresh,
                255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                15,
                3
        );
        blurred.release();

        clearBorders(thresh);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        Rect bestRect = null;
        double maxArea = 0;
        double cellArea = cell.rows() * cell.cols();

        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);
            double area = Imgproc.contourArea(contour);
            double aspectRatio = rect.height == 0 ? 0 : (double) rect.width / rect.height;

            if (area > cellArea * 0.03 && area < cellArea * 0.80 && aspectRatio > 0.10 && aspectRatio < 1.50) {
                if (area > maxArea) {
                    maxArea = area;
                    bestRect = rect;
                }
            }
            contour.release();
        }

        hierarchy.release();

        if (bestRect == null) {
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

    private void clearBorders(Mat binary) {
        int w = binary.cols();
        int h = binary.rows();
        Mat mask = new Mat(h + 2, w + 2, CvType.CV_8UC1, new Scalar(0));
        Scalar black = new Scalar(0);

        for (int i = 0; i < w; i++) {
            if (binary.get(0, i)[0] == 255) Imgproc.floodFill(binary, mask, new Point(i, 0), black);
            if (binary.get(h - 1, i)[0] == 255) Imgproc.floodFill(binary, mask, new Point(i, h - 1), black);
        }
        for (int i = 0; i < h; i++) {
            if (binary.get(i, 0)[0] == 255) Imgproc.floodFill(binary, mask, new Point(0, i), black);
            if (binary.get(i, w - 1)[0] == 255) Imgproc.floodFill(binary, mask, new Point(w - 1, i), black);
        }

        mask.release();
    }

    private void createBoard() {
        int blockSize = (int) Math.sqrt(n);
        if (blockSize == 0) blockSize = 3;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int cellSize = (screenWidth - 64) / n;

        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int index = r * n + c;
                EditText cell = new EditText(this);
                cell.setId(View.generateViewId());
                cellArray[index] = cell;

                GridLayout.LayoutParams params = new GridLayout.LayoutParams(GridLayout.spec(r), GridLayout.spec(c));
                params.width = cellSize;
                params.height = cellSize;
                params.setMargins(1, 1, 1, 1);
                cell.setLayoutParams(params);

                cell.setGravity(Gravity.CENTER);
                cell.setTextSize(n > 16 ? 12 : 18);
                cell.setTextColor(Color.BLACK);
                cell.setInputType(InputType.TYPE_CLASS_NUMBER);
                cell.setBackground(null);
                cell.setTag(index);

                cell.setOnLongClickListener(v -> {
                    int pos = (int) v.getTag();
                    if (debugCellImages != null && debugCellImages[pos] != null) {
                        showDebugCell(debugCellImages[pos], pos);
                        return true;
                    } else {
                        Toast.makeText(this, "No image captured for cell " + pos, Toast.LENGTH_SHORT).show();
                        return true;
                    }
                });

                if (n == 9) {
                    cell.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1)});
                }

                if (((r / blockSize) + (c / blockSize)) % 2 == 0) {
                    cell.setBackgroundColor(Color.parseColor("#E0E0E0"));
                } else {
                    cell.setBackgroundColor(Color.parseColor("#FFFFFF"));
                }
                sudokuGrid.addView(cell);
            }
        }
    }

    private void showDebugCell(Bitmap bitmap, int index) {
        ImageView imageView = new ImageView(this);
        imageView.setImageBitmap(bitmap);
        imageView.setAdjustViewBounds(true);
        imageView.setPadding(32, 32, 32, 32);

        int r = index / n;
        int c = index % n;

        new AlertDialog.Builder(this)
                .setTitle("Cell Debug [" + r + "," + c + "]")
                .setView(imageView)
                .setPositiveButton("Save to Gallery", (dialog, which) -> saveBitmapToGallery(bitmap, "cell_" + r + "_" + c))
                .setNegativeButton("Close", null)
                .show();
    }

    private void saveBitmapToGallery(Bitmap bitmap, String name) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SudokuDebug");

        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri != null) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                    Toast.makeText(this, "Saved to Gallery", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Failed to open output stream", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Failed to save: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }
}
