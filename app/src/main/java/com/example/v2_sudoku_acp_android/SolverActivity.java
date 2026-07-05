package com.example.v2_sudoku_acp_android;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

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

    private void processFullBoard() {
        Mat fullImage = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_GRAYSCALE);
        if (fullImage.empty()) {
            Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show();
            return;
        }

        // Contrast Normalization
        Core.normalize(fullImage, fullImage, 0, 255, Core.NORM_MINMAX);

        int cellW = fullImage.cols() / n;
        int cellH = fullImage.rows() / n;

        int insetX = (int) (cellW * 0.15);
        int insetY = (int) (cellH * 0.15);

        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                Rect cellRoi = new Rect(
                        (c * cellW) + insetX,
                        (r * cellH) + insetY,
                        cellW - (2 * insetX),
                        cellH - (2 * insetY)
                );

                Mat cellMat = new Mat(fullImage, cellRoi);
                
                int index = r * n + c;

                // Save for debug purposes
                Bitmap debugBmp = Bitmap.createBitmap(cellMat.cols(), cellMat.rows(), Bitmap.Config.ARGB_8888);
                Utils.matToBitmap(cellMat, debugBmp);
                debugCellImages[index] = debugBmp;

                Mat digitMat = extractDigit(cellMat);
                if (digitMat != null) {
                    Bitmap cellBmp = Bitmap.createBitmap(digitMat.cols(), digitMat.rows(), Bitmap.Config.ARGB_8888);
                    Utils.matToBitmap(digitMat, cellBmp);
                    
                    int digit = digitRecognizer.recognize(cellBmp);
                    if (digit > 0) {
                        cellArray[index].setText(String.valueOf(digit));
                        cellArray[index].setTextColor(Color.BLUE);
                    } else {
                        cellArray[index].setText("");
                    }
                    digitMat.release();
                } else {
                    cellArray[index].setText("");
                }
                cellMat.release();
            }
        }

        fullImage.release();
        Toast.makeText(this, "Scan Complete", Toast.LENGTH_SHORT).show();
    }

    private Mat extractDigit(Mat cell) {
        Mat thresh = new Mat();
        Imgproc.adaptiveThreshold(cell, thresh, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 15, 3);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        Mat result = null;
        double maxArea = 0;
        Rect bestRect = null;
        double cellArea = cell.total();

        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);
            double area = Imgproc.contourArea(contour);
            double aspectRatio = (double) rect.width / rect.height;

            // MNIST-like filtering
            if (area > cellArea * 0.05 && area < cellArea * 0.8 && aspectRatio > 0.1 && aspectRatio < 1.5) {
                if (area > maxArea) {
                    maxArea = area;
                    bestRect = rect;
                }
            }
        }

        if (bestRect != null) {
            // Extract the digit from the thresholded image
            Mat digit = new Mat(thresh, bestRect);
            
            // Create a square container with padding (standard for MNIST)
            int size = Math.max(bestRect.width, bestRect.height);
            int padding = size / 4;
            int finalSize = size + 2 * padding;
            
            Mat centered = new Mat(finalSize, finalSize, thresh.type(), new org.opencv.core.Scalar(0));
            int xOffset = (finalSize - bestRect.width) / 2;
            int yOffset = (finalSize - bestRect.height) / 2;
            
            digit.copyTo(centered.submat(new Rect(xOffset, yOffset, bestRect.width, bestRect.height)));
            result = centered;
            digit.release();
        }

        thresh.release();
        hierarchy.release();
        return result;
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
                cell.setBackground(null); // Remove default underline
                cell.setTag(index);

                // Long click for debug view
                cell.setOnLongClickListener(v -> {
                    int pos = (int) v.getTag();
                    if (debugCellImages != null && debugCellImages[pos] != null) {
                        showDebugCell(debugCellImages[pos], pos);
                        return true;
                    } else {
                        Toast.makeText(this, "No image captured for cell " + pos, Toast.LENGTH_SHORT).show();
                        return true; // Return true so we don't trigger standard long-press behavior
                    }
                });

                // Limit input to 1 character for standard Sudoku
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
                .setPositiveButton("OK", null)
                .show();
    }
}
