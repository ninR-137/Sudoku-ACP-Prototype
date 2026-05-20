package com.example.v2_sudoku_acp_android;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.Arrays;

import org.opencv.core.Size;

import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.android.Utils;

public class MainActivity extends AppCompatActivity {

    private ImageView ivSudokuPreview;
    private TextView tvSudokuStatus;

    private Button btnProcess;
    private int detectedGridSize = 0;
    private String lastCapturedImagePath = null;

    private Mat originalWarpedMat; // The raw grayscale image
    private SeekBar sbManualThreshold;
    private TextView tvManualThresholdLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        if (OpenCVLoader.initLocal()) {
            Log.i("OpenCV", "OpenCV loaded successfully");
        }


        ivSudokuPreview = findViewById(R.id.ivSudokuPreview);
        tvSudokuStatus = findViewById(R.id.tvSudokuStatus);
        Button btnCamera = findViewById(R.id.btnCamera);
        Button btnGallery = findViewById(R.id.btnGallery);

        sbManualThreshold = findViewById(R.id.sbManualThreshold);
        tvManualThresholdLabel = findViewById(R.id.tvManualThresholdLabel);

        sbManualThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateImageThreshold(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Camera Button: Check permissions first, then start CameraActivity
        btnCamera.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                Intent intent = new Intent(MainActivity.this, CameraActivity.class);
                // USE THE LAUNCHER instead of startActivity
                cameraResultLauncher.launch(intent);
            } else {
                checkCameraPermission();
            }
        });

        // Gallery Button: Launch the modern Photo Picker
        btnGallery.setOnClickListener(v -> {
            pickMedia.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        });

        btnProcess = findViewById(R.id.btnProcess);
        btnProcess.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SolverActivity.class);
            intent.putExtra("grid_size", detectedGridSize);
            intent.putExtra("image_path", lastCapturedImagePath);
            startActivity(intent);
        });

        checkCameraPermission();

    }

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.i("Permission", "Camera permission granted");
                } else {
                    Toast.makeText(this, "Camera permission is required to scan Sudoku", Toast.LENGTH_LONG).show();
                }
            });
    private final ActivityResultLauncher<String> requestPhotoPermissionLauncher =registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
        if (isGranted) {
            Log.i("Permission", "Photo access granted");
        } else {
            Toast.makeText(this, "Photo access is required to pick Sudoku images", Toast.LENGTH_LONG).show();
        }
    });
    private void checkCameraPermission() {

        if(ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            Log.i("Permission", "Camera permission already granted");
        }
        // 2. Check if we should show an explanation (Rationale)
        else if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            showCameraRationaleDialog();
        }
        // 3. First time asking or "Don't ask again" not checked yet
        else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }

    }
    private void checkPhotoPermission() {
        String permissionNeeded;    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ uses specific media image permission
            permissionNeeded = Manifest.permission.READ_MEDIA_IMAGES;
        } else {
            // Android 12 and below uses general storage permission
            permissionNeeded = Manifest.permission.READ_EXTERNAL_STORAGE;
        }

        if (ContextCompat.checkSelfPermission(this, permissionNeeded) == PackageManager.PERMISSION_GRANTED) {
            Log.i("Permission", "Photo permission already granted");
        } else {
            requestPhotoPermissionLauncher.launch(permissionNeeded);
        }
    }
    private void showCameraRationaleDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Camera Permission Needed")
                .setMessage("This app uses the camera to scan Sudoku puzzles from paper. Please allow camera access.")
                .setPositiveButton("OK", (dialog, which) -> {
                    // Ask again after explanation
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA);
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .create()
                .show();
    }

    private final ActivityResultLauncher<Intent> cameraResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String imagePath = result.getData().getStringExtra("image_path");
                    int gridSize = result.getData().getIntExtra("grid_size", 9);

                    if (imagePath != null) {
                        lastCapturedImagePath = imagePath;
                        detectedGridSize = gridSize;

                        Mat src = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_GRAYSCALE);
                        if (src.empty()) return;

                        // --- DYNAMIC SCALING LOGIC ---
                        // 9x9   -> 1000x1000 (2x)
                        // 16x16 -> 1600x1600 (3.2x)
                        // 25x25 -> 2000x2000 (4x)
                        float scaleFactor = 2.0f;
                        if (gridSize == 16) scaleFactor = 3.2f;
                        else if (gridSize == 25) scaleFactor = 4.0f;

                        int newSize = (int) (src.cols() * scaleFactor);

                        Mat resized = new Mat();
                        Imgproc.resize(src, resized, new Size(newSize, newSize), 0, 0, Imgproc.INTER_CUBIC);

                        // Initialize the global Mat for sliders
                        if (originalWarpedMat != null) originalWarpedMat.release();
                        originalWarpedMat = resized.clone();

                        // Save this high-res version to disk for SolverActivity
                        Imgcodecs.imwrite(imagePath, resized);

                        // Update Display (ImageView handles the "consistent size" via centerInside)
                        Mat displayMat = new Mat();
                        Imgproc.cvtColor(resized, displayMat, Imgproc.COLOR_GRAY2RGBA);
                        Bitmap bmp = Bitmap.createBitmap(displayMat.cols(), displayMat.rows(), Bitmap.Config.ARGB_8888);
                        Utils.matToBitmap(displayMat, bmp);
                        ivSudokuPreview.setImageBitmap(bmp);

                        src.release();
                        resized.release();
                        displayMat.release();

                        tvSudokuStatus.setText("Detected: " + gridSize + "x" + gridSize);
                        btnProcess.setVisibility(View.VISIBLE);
                        sbManualThreshold.setVisibility(View.VISIBLE);
                        tvManualThresholdLabel.setVisibility(View.VISIBLE);

                        sbManualThreshold.setProgress(128);
                        updateImageThreshold(128);
                    }
                }
            }
    );

    private void updateImageThreshold(int progress) {
        if (originalWarpedMat == null || originalWarpedMat.empty()) return;

        Mat temp = new Mat();
        Mat processedMat = new Mat();

        // 1. Contrast Normalization
        Core.normalize(originalWarpedMat, temp, 0, 255, Core.NORM_MINMAX);

        // 2. Bilateral Filter (Smooth paper)
        Mat smoothed = new Mat();
        Imgproc.bilateralFilter(temp, smoothed, 9, 75, 75);

        // 3. Sharpen (Enhance digit edges)
        Mat sharpened = new Mat();
        sharpenMat(smoothed, sharpened);

        // 4. Adaptive Thresholding
        // progress / 10 - 10 allows the user to fine-tune the noise floor
        int c_val = (progress / 10) - 10;
        Imgproc.adaptiveThreshold(sharpened, processedMat, 255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY, 11, c_val);

        // 5. Morphological Cleanup
        // Using org.opencv.core.Size explicitly to avoid ambiguity
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new org.opencv.core.Size(2, 2));
        Imgproc.morphologyEx(processedMat, processedMat, Imgproc.MORPH_CLOSE, kernel);

        // Display
        Bitmap bmp = Bitmap.createBitmap(processedMat.cols(), processedMat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(processedMat, bmp);
        ivSudokuPreview.setImageBitmap(bmp);

        // Save
        File file = new File(getExternalFilesDir(null), "captured_sudoku.jpg");
        Imgcodecs.imwrite(file.getAbsolutePath(), processedMat);

        // Cleanup
        temp.release();
        smoothed.release();
        sharpened.release();
        processedMat.release();
        kernel.release();
    }

    /**
     * Enhances high-frequency details (digit edges) by subtracting a
     * blurred version of the image from the original (Unsharp Masking).
     */
    private void sharpenMat(Mat src, Mat dst) {
        Mat blurred = new Mat();
        // Sigma 3 provides a decent balance between sharpening and noise
        Imgproc.GaussianBlur(src, blurred, new org.opencv.core.Size(0, 0), 3);
        // Formula:   = src * 1.5 + blurred * -0.5 + 0
        Core.addWeighted(src, 1.5, blurred, -0.5, 0, dst);
        blurred.release();
    }

    private final ActivityResultLauncher<PickVisualMediaRequest> pickMedia =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    Log.d("PhotoPicker", "Selected URI: " + uri);
                    ivSudokuPreview.setImageURI(uri); // Show image in ImageView
                } else {
                    Log.d("PhotoPicker", "No media selected");
                }
            });


}