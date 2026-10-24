// CameraX camera screen, kept for reference and currently disabled: the Camera button now opens
// the phone's own camera app (see MainActivity.takePictureLauncher).
//
// To restore it:
//   1. Remove the /* and */ around this file.
//   2. Uncomment the CameraActivity <activity> entry in AndroidManifest.xml.
//   3. In MainActivity, uncomment cameraResultLauncher and the CameraActivity launch in the
//      Camera button's click listener, and remove the takePictureLauncher call there.
//
// It shows live board and grid-size detection while aiming, takes a full-resolution photo
// (capped near 12 MP) and returns its path plus the detected grid size. The layout is
// res/layout/activity_camera.xml, and the CameraX libraries are still in app/build.gradle.kts.

/*
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Live board and grid-size detection on the preview frames, for feedback only. Capture takes a
// full-resolution color photo; board selection then happens in ProcessingActivity, with the
// detected grid size preselected.
public class CameraActivity extends AppCompatActivity {
    private static final String TAG = "SudokuCamera";
    // About 12 MP. Phones with 50-200 MP sensors would otherwise produce photos too large to
    // process in memory, while this still leaves enough detail for small digits.
    private static final android.util.Size TARGET_PHOTO_SIZE = new android.util.Size(4032, 3024);

    private PreviewView viewFinder;
    private ImageView ivDebugFrame;
    private TextView tvDimensions;
    private TextView tvThresholdValue;
    private ExecutorService cameraExecutor;
    private ImageCapture imageCapture;
    private Button btnCapture;

    private int currentThreshold = 30;
    private int lockedDimension = 0; // 0 = Auto
    private int stableDimension = 0;

    private final List<Integer> resultHistory = new ArrayList<>();
    private final int HISTORY_SIZE = 20;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        viewFinder = findViewById(R.id.viewFinder);
        ivDebugFrame = findViewById(R.id.ivDebugFrame);
        tvDimensions = findViewById(R.id.tvDimensions);
        tvThresholdValue = findViewById(R.id.tvThresholdValue);
        btnCapture = findViewById(R.id.btnCapture);
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

                ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(TARGET_PHOTO_SIZE,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build();
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setResolutionSelector(resolutionSelector)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis, imageCapture);
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

            }

            // Draw and Display
            Mat displayMat = new Mat();
            // Show the camera frame itself (not the thresholded mask) with the detected board outlined
            Imgproc.cvtColor(grayMat, displayMat, Imgproc.COLOR_GRAY2RGBA);
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

    private void takePhoto() {
        if (imageCapture == null) return;
        btnCapture.setEnabled(false);
        final int gridSize = stableDimension;

        // The photo's orientation is stored in its EXIF data, which OpenCV's imread applies on load
        File file = new File(getExternalFilesDir(null), "camera_photo.jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(file).build();

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                Intent intent = new Intent();
                intent.putExtra("image_path", file.getAbsolutePath());
                intent.putExtra("grid_size", gridSize);
                setResult(RESULT_OK, intent);
                finish();
            }

            @Override
            public void onError(@NonNull ImageCaptureException e) {
                Log.e(TAG, "Photo capture failed", e);
                btnCapture.setEnabled(true);
                Toast.makeText(CameraActivity.this, "Capture failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}
*/
