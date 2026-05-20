package com.example.v2_sudoku_acp_android;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraActivity extends AppCompatActivity {

    private PreviewView viewFinder;
    private ImageView ivDebugFrame;
    private TextView tvDimensions;
    private TextView tvThresholdValue;
    private ExecutorService cameraExecutor;

    private int currentThreshold = 30;
    private int lockedDimension = 0; // 0 = Auto
    private int stableDimension = 0;

    private final List<Integer> resultHistory = new ArrayList<>();
    private final int HISTORY_SIZE = 20;

    private Mat latestSudokuMat;
    private final Object matLock = new Object();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        viewFinder = findViewById(R.id.viewFinder);
        ivDebugFrame = findViewById(R.id.ivDebugFrame);
        tvDimensions = findViewById(R.id.tvDimensions);
        tvThresholdValue = findViewById(R.id.tvThresholdValue);
        Button btnCapture = findViewById(R.id.btnCapture);
        SeekBar thresholdSeekBar = findViewById(R.id.thresholdSeekBar);
        RadioGroup rgGridLock = findViewById(R.id.rgGridLock);

        thresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentThreshold = progress;
                tvThresholdValue.setText("Threshold: " + progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        rgGridLock.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb9x9) lockedDimension = 9;
            else if (checkedId == R.id.rb16x16) lockedDimension = 16;
            else if (checkedId == R.id.rb25x25) lockedDimension = 25;
            else lockedDimension = 0;

            resultHistory.clear();
            stableDimension = lockedDimension;
        });

        btnCapture.setOnClickListener(v -> takePhoto());

        cameraExecutor = Executors.newSingleThreadExecutor();
        startCamera();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);
            } catch (Exception e) {
                Log.e("SudokuCamera", "Camera binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImageProxy(ImageProxy image) {
        try {
            int rotationDegrees = image.getImageInfo().getRotationDegrees();

            // 1. Convert to Gray
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            java.nio.ByteBuffer buffer = plane.getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            Mat grayMat = new Mat(image.getHeight(), image.getWidth(), CvType.CV_8UC1);
            grayMat.put(0, 0, data);

            // 2. Rotate to match screen
            if (rotationDegrees != 0) {
                Mat rotatedMat = new Mat();
                if (rotationDegrees == 90) Core.rotate(grayMat, rotatedMat, Core.ROTATE_90_CLOCKWISE);
                else if (rotationDegrees == 180) Core.rotate(grayMat, rotatedMat, Core.ROTATE_180);
                else if (rotationDegrees == 270) Core.rotate(grayMat, rotatedMat, Core.ROTATE_90_COUNTERCLOCKWISE);
                grayMat.release();
                grayMat = rotatedMat;
            }

            // 3. Thresholding & Healing
            Mat binaryMat = new Mat();
            int blockSize = Math.max(11, (grayMat.cols() / 50) | 1);
            Imgproc.adaptiveThreshold(grayMat, binaryMat, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, blockSize, currentThreshold / 10 + 2);

            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_CROSS, new Size(3, 3));
            Imgproc.morphologyEx(binaryMat, binaryMat, Imgproc.MORPH_CLOSE, kernel);
            kernel.release();

            // 4. Find Contours
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(binaryMat, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);

            MatOfPoint largestContour = null;
            int largestIdx = -1;
            double maxArea = 0;

            for (int i = 0; i < contours.size(); i++) {
                double area = Imgproc.contourArea(contours.get(i));
                if (area > 5000) {
                    MatOfPoint2f c2f = new MatOfPoint2f(contours.get(i).toArray());
                    double peri = Imgproc.arcLength(c2f, true);
                    MatOfPoint2f approx = new MatOfPoint2f();
                    Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true);
                    if (approx.total() == 4 && area > maxArea) {
                        maxArea = area;
                        largestContour = contours.get(i);
                        largestIdx = i;
                    }
                    approx.release();
                    c2f.release();
                }
            }

            String dimText = "No Grid Found";
            if (largestIdx != -1) {
                int frameN = -1;
                if (lockedDimension == 0) {
                    Rect gridRect = Imgproc.boundingRect(largestContour);
                    List<Integer> cellWidths = new ArrayList<>();
                    for (int i = 0; i < contours.size(); i++) {
                        if ((int) hierarchy.get(0, i)[3] == largestIdx) {
                            Rect cellRect = Imgproc.boundingRect(contours.get(i));
                            if (cellRect.width > gridRect.width / 50 && cellRect.width < gridRect.width / 2) {
                                cellWidths.add(cellRect.width);
                            }
                        }
                    }
                    if (!cellWidths.isEmpty()) {
                        Collections.sort(cellWidths);
                        double estN = (double) gridRect.width / cellWidths.get(cellWidths.size() / 2);
                        int[] targets = {9, 16, 25};
                        double minDiff = 100;
                        for (int t : targets) {
                            double d = Math.abs(estN - t);
                            if (d < minDiff) { minDiff = d; frameN = t; }
                        }
                        if (minDiff > 4.0) frameN = -1;
                    }
                } else {
                    frameN = lockedDimension;
                }

                // Stability Voting
                if (frameN != -1) {
                    resultHistory.add(frameN);
                    if (resultHistory.size() > HISTORY_SIZE) resultHistory.remove(0);
                    Map<Integer, Integer> counts = new HashMap<>();
                    for (int v : resultHistory) counts.put(v, counts.getOrDefault(v, 0) + 1);
                    int mostFreq = -1, maxC = 0;
                    for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
                        if (e.getValue() > maxC) { maxC = e.getValue(); mostFreq = e.getKey(); }
                    }
                    if (maxC > (HISTORY_SIZE / 2)) stableDimension = mostFreq;
                }

                dimText = (stableDimension > 0) ? stableDimension + "x" + stableDimension + (lockedDimension > 0 ? " (Locked)" : " Grid") : "Analyzing...";

                // Perspective Warp for Capture
                MatOfPoint2f c2f = new MatOfPoint2f(largestContour.toArray());
                MatOfPoint2f approx = new MatOfPoint2f();
                Imgproc.approxPolyDP(c2f, approx, 0.02 * Imgproc.arcLength(c2f, true), true);
                if (approx.total() == 4) {
                    synchronized (matLock) {
                        if (latestSudokuMat != null) latestSudokuMat.release();
                        // Use binaryMat for the "processed" (thresholded) warped grid
                        latestSudokuMat = warpPerspective(binaryMat, approx);
                    }
                }
                approx.release();
                c2f.release();
            }

            // Draw and Display
            Mat displayMat = new Mat();
            Imgproc.cvtColor(binaryMat, displayMat, Imgproc.COLOR_GRAY2RGBA);
            if (largestContour != null) {
                Imgproc.drawContours(displayMat, Collections.singletonList(largestContour), -1, new Scalar(0, 255, 0, 255), 4);
            }

            Bitmap bmp = Bitmap.createBitmap(displayMat.cols(), displayMat.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(displayMat, bmp);

            final String finalDim = dimText;
            runOnUiThread(() -> {
                ivDebugFrame.setImageBitmap(bmp);
                tvDimensions.setText(finalDim);
            });

            grayMat.release();
            binaryMat.release();
            displayMat.release();
            hierarchy.release();
        } catch (Exception e) {
            Log.e("Sudoku", "Analyzer error", e);
        } finally {
            image.close();
        }
    }

    private Mat warpPerspective(Mat src, MatOfPoint2f corners) {
        Point[] pts = corners.toArray();
        Point[] sorted = new Point[4];
        double[] sum = new double[4], diff = new double[4];
        for(int i=0; i<4; i++) { sum[i] = pts[i].x + pts[i].y; diff[i] = pts[i].x - pts[i].y; }

        int tl=0, br=0, tr=0, bl=0;
        for(int i=1; i<4; i++) {
            if (sum[i] < sum[tl]) tl = i;
            if (sum[i] > sum[br]) br = i;
            if (diff[i] > diff[tr]) tr = i;
            if (diff[i] < diff[bl]) bl = i;
        }

        MatOfPoint2f srcPts = new MatOfPoint2f(pts[tl], pts[tr], pts[br], pts[bl]);
        MatOfPoint2f dstPts = new MatOfPoint2f(new Point(0, 0), new Point(500, 0), new Point(500, 500), new Point(0, 500));
        Mat m = Imgproc.getPerspectiveTransform(srcPts, dstPts);
        Mat dst = new Mat();
        Imgproc.warpPerspective(src, dst, m, new Size(500, 500));
        m.release();
        srcPts.release();
        dstPts.release();
        return dst;
    }

    // Inside CameraActivity.java

    private void takePhoto() {
        synchronized (matLock) {
            if (latestSudokuMat == null || latestSudokuMat.empty()) {
                Toast.makeText(this, "No grid detected!", Toast.LENGTH_SHORT).show();
                return;
            }

            Bitmap bmp = Bitmap.createBitmap(latestSudokuMat.cols(), latestSudokuMat.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(latestSudokuMat, bmp);

            File file = new File(getExternalFilesDir(null), "captured_sudoku.jpg");
            try (FileOutputStream out = new FileOutputStream(file)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 100, out);

                Intent intent = new Intent();
                intent.putExtra("image_path", file.getAbsolutePath());

                // --- ADDED: Pass the detected grid size ---
                intent.putExtra("grid_size", stableDimension);

                setResult(RESULT_OK, intent);
                finish();
            } catch (Exception e) {
                Log.e("Sudoku", "Save failed", e);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        synchronized (matLock) { if (latestSudokuMat != null) latestSudokuMat.release(); }
    }
}