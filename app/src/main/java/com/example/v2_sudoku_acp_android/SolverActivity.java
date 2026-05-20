package com.example.v2_sudoku_acp_android;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
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
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

public class SolverActivity extends AppCompatActivity {
    private GridLayout sudokuGrid;
    private TextView[] cellArray;
    private int n; // Grid Size (9, 16, 25)
    private String imagePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_solver);

        // Retrieve data from Intent
        n = getIntent().getIntExtra("grid_size", 9);
        imagePath = getIntent().getStringExtra("image_path");

        // Safety check for grid size
        if (n <= 0) n = 9;

        sudokuGrid = findViewById(R.id.sudokuGrid);

        // Configure the Grid
        sudokuGrid.setRowCount(n);
        sudokuGrid.setColumnCount(n);

        cellArray = new TextView[n * n];
        createBoard();

        // Inside onCreate
        if (imagePath != null) {
            Toast.makeText(this, "Processing Full Board...", Toast.LENGTH_SHORT).show();
            processFullBoard(); // Changed from processFirstRow
        }
    }

    private void processFullBoard() {
        // 1. Load the captured image (normalized by the user's slider in MainActivity)
        Mat fullGrid = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_GRAYSCALE);
        if (fullGrid.empty()) {
            Toast.makeText(this, "Error: Could not load image", Toast.LENGTH_SHORT).show();
            return;
        }

        // 2. Contrast Normalization to ensure clear black/white separation
        Core.normalize(fullGrid, fullGrid, 0, 255, Core.NORM_MINMAX);

        // 3. Calculate cell dimensions
        int cellW = fullGrid.cols() / n;
        int cellH = fullGrid.rows() / n;

        // Insets to avoid picking up the grid lines
        int insetX = (int) (cellW * 0.18);
        int insetY = (int) (cellH * 0.18);

        // 4. NESTED LOOP: Iterate through every row (r) and column (c)
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                // Calculate ROI for the current cell
                Rect cellRoi = new Rect(
                        (c * cellW) + insetX,
                        (r * cellH) + insetY, // Now dynamic based on row
                        cellW - (2 * insetX),
                        cellH - (2 * insetY)
                );

                // Slice the cell from the full grid
                Mat cellMat = new Mat(fullGrid, cellRoi);

                // Detect if a digit exists using your existing logic
                boolean hasDigit = detectDigit(cellMat);

                // Update the UI at row r, column c
                int index = r * n + c;
                if (hasDigit) {
                    cellArray[index].setText("X");
                    cellArray[index].setTextColor(Color.RED);
                } else {
                    cellArray[index].setText("");
                }

                // Important: release the small Mat
                cellMat.release();
            }
        }

        // Cleanup
        fullGrid.release();
        Toast.makeText(this, "Board Scan Complete", Toast.LENGTH_SHORT).show();
    }

    private boolean detectDigit(Mat cell) {
        Mat thresh = new Mat();

        // 1. Adaptive Threshold to get high contrast
        Imgproc.adaptiveThreshold(cell, thresh, 255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, 15, 3);

        // 2. Remove tiny noise specks
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new org.opencv.core.Size(2, 2));
        Imgproc.morphologyEx(thresh, thresh, Imgproc.MORPH_OPEN, kernel);
        kernel.release();

        // 3. Find all "blobs" (contours) in the cell
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        boolean digitFound = false;
        double cellArea = cell.total();

        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);
            double area = Imgproc.contourArea(contour);

            // --- FILTERING LOGIC ---
            // A digit should:
            // 1. Not be too tiny (noise)
            // 2. Not be the entire cell (grid lines)
            // 3. Have a reasonable Aspect Ratio (not a super thin horizontal/vertical line)

            double aspectRatio = (double) rect.width / rect.height;

            boolean isRightSize = (area > cellArea * 0.05) && (area < cellArea * 0.7);
            boolean isRightShape = (aspectRatio > 0.2 && aspectRatio < 1.2);

            // Ensure it's somewhat centered (digits aren't stuck to the very top/bottom)
            boolean isCentered = rect.y + rect.height/2.0 > cell.rows() * 0.1 &&
                    rect.y + rect.height/2.0 < cell.rows() * 0.9;

            if (isRightSize && isRightShape && isCentered) {
                digitFound = true;
                break;
            }
        }

        thresh.release();
        hierarchy.release();
        return digitFound;
    }
    private void createBoard() {
        int blockSize = (int) Math.sqrt(n);
        if (blockSize == 0) blockSize = 3; // Fallback

        // Calculate cell size based on screen width
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int cellSize = (screenWidth - 64) / n; // 64dp for padding/margins

        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int index = r * n + c;

                TextView cell = new TextView(this);

                // Set the ID and store in array
                cell.setId(View.generateViewId());
                cellArray[index] = cell;

                // Position in GridLayout
                GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                        GridLayout.spec(r),
                        GridLayout.spec(c)
                );
                params.width = cellSize;
                params.height = cellSize;
                params.setMargins(1, 1, 1, 1);
                cell.setLayoutParams(params);

                // Styling
                cell.setGravity(Gravity.CENTER);
                cell.setTextSize(n > 16 ? 12 : 18); // Smaller font for 25x25
                cell.setTextColor(Color.BLACK);

                // Alternating block colors logic: (row/blockSize + col/blockSize) % 2
                if (((r / blockSize) + (c / blockSize)) % 2 == 0) {
                    cell.setBackgroundColor(Color.parseColor("#E0E0E0")); // Light Gray block
                } else {
                    cell.setBackgroundColor(Color.parseColor("#FFFFFF")); // White block
                }

                sudokuGrid.addView(cell);
            }
        }
    }
}
