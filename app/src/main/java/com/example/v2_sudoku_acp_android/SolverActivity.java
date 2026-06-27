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

import java.util.ArrayList;
import java.util.List;

public class SolverActivity extends AppCompatActivity {
    private GridLayout sudokuGrid;
    private TextView[] cellArray;
    private int n; 
    private String imagePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_solver);

        n = getIntent().getIntExtra("grid_size", 9);
        imagePath = getIntent().getStringExtra("image_path");

        if (n <= 0) n = 9;

        sudokuGrid = findViewById(R.id.sudokuGrid);
        sudokuGrid.setRowCount(n);
        sudokuGrid.setColumnCount(n);

        cellArray = new TextView[n * n];
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
                boolean hasDigit = detectDigit(cellMat);

                int index = r * n + c;
                if (hasDigit) {
                    cellArray[index].setText("X");
                    cellArray[index].setTextColor(Color.RED);
                } else {
                    cellArray[index].setText("");
                }
                cellMat.release();
            }
        }

        fullImage.release();
        Toast.makeText(this, "Scan Complete", Toast.LENGTH_SHORT).show();
    }

    private boolean detectDigit(Mat cell) {
        Mat thresh = new Mat();
        Imgproc.adaptiveThreshold(cell, thresh, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 15, 3);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        boolean digitFound = false;
        double cellArea = cell.total();

        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);
            double area = Imgproc.contourArea(contour);
            double aspectRatio = (double) rect.width / rect.height;

            boolean isRightSize = (area > cellArea * 0.05) && (area < cellArea * 0.7);
            boolean isRightShape = (aspectRatio > 0.15 && aspectRatio < 1.3);

            if (isRightSize && isRightShape) {
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
        if (blockSize == 0) blockSize = 3;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int cellSize = (screenWidth - 64) / n;

        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int index = r * n + c;
                TextView cell = new TextView(this);
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

                if (((r / blockSize) + (c / blockSize)) % 2 == 0) {
                    cell.setBackgroundColor(Color.parseColor("#E0E0E0"));
                } else {
                    cell.setBackgroundColor(Color.parseColor("#FFFFFF"));
                }
                sudokuGrid.addView(cell);
            }
        }
    }
}
