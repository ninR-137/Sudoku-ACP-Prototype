package com.example.v2_sudoku_acp_android;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
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
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    private ImageView ivSudokuPreview;
    private TextView tvSudokuStatus;
    private ImageButton btnEditImage;

    private Button btnProcess, btnCrop;
    private int detectedGridSize = 9;

    private Mat originalWarpedMat; 
    private SeekBar sbMedianBlurSlider, sbMorphologySlider;
    private SwitchCompat swMedianBlurToggle, swMorphologyToggle, swInvertToggle;


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
        btnEditImage = findViewById(R.id.btnEditImage);
        Button btnCamera = findViewById(R.id.btnCamera);
        Button btnGallery = findViewById(R.id.btnGallery);
        Button btnRotateLeft = findViewById(R.id.btnRotateLeft);
        Button btnRotateRight = findViewById(R.id.btnRotateRight);

        swMedianBlurToggle = findViewById(R.id.swMedianBlur);
        sbMedianBlurSlider = findViewById(R.id.sbMedianBlur);

        swMorphologyToggle = findViewById(R.id.swMorphology);
        sbMorphologySlider = findViewById(R.id.sbMorphology);
        swInvertToggle = findViewById(R.id.swInvert);

        SeekBar.OnSeekBarChangeListener updateListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateImageThreshold();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };

        sbMedianBlurSlider.setOnSeekBarChangeListener(updateListener);
        sbMorphologySlider.setOnSeekBarChangeListener(updateListener);

        swMedianBlurToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sbMedianBlurSlider.setEnabled(isChecked);
            updateImageThreshold();
        });

        swMorphologyToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sbMorphologySlider.setEnabled(isChecked);
            updateImageThreshold();
        });

        swInvertToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateImageThreshold();
        });

        btnRotateLeft.setOnClickListener(v -> rotateImage(false));
        btnRotateRight.setOnClickListener(v -> rotateImage(true));

        btnCrop = findViewById(R.id.btnCrop);
        btnCrop.setOnClickListener(v -> startCropIntent());

        btnEditImage.setOnClickListener(v -> {
            File file = new File(getExternalFilesDir(null), "captured_sudoku.jpg");
            Intent intent = new Intent(MainActivity.this, EditImageActivity.class);
            intent.putExtra("image_path", file.getAbsolutePath());
            editResultLauncher.launch(intent);
        });

        btnCamera.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                Intent intent = new Intent(MainActivity.this, CameraActivity.class);
                cameraResultLauncher.launch(intent);
            } else {
                checkCameraPermission();
            }
        });

        btnGallery.setOnClickListener(v -> {
            pickMedia.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        });

        btnProcess = findViewById(R.id.btnProcess);
        btnProcess.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SolverActivity.class);
            intent.putExtra("grid_size", detectedGridSize);
            
            File processedFile = new File(getExternalFilesDir(null), "captured_sudoku.jpg");
            intent.putExtra("image_path", processedFile.getAbsolutePath());
            startActivity(intent);
        });

        checkCameraPermission();
    }

    private void rotateImage(boolean clockwise) {
        if (originalWarpedMat == null || originalWarpedMat.empty()) return;
        Mat rotated = new Mat();
        if (clockwise) {
            Core.rotate(originalWarpedMat, rotated, Core.ROTATE_90_CLOCKWISE);
        } else {
            Core.rotate(originalWarpedMat, rotated, Core.ROTATE_90_COUNTERCLOCKWISE);
        }
        originalWarpedMat.release();
        originalWarpedMat = rotated;
        updateImageThreshold();
    }

    private void checkCameraPermission() {
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            Log.i("Permission", "Camera permission already granted");
        } else if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            showCameraRationaleDialog();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void showCameraRationaleDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Camera Permission Needed")
                .setMessage("This app uses the camera to scan Sudoku puzzles from paper. Please allow camera access.")
                .setPositiveButton("OK", (dialog, which) -> {
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA);
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .create()
                .show();
    }

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted) {
                    Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<Intent> cameraResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    btnEditImage.setVisibility(View.VISIBLE);
                    handleProcessingResult(result.getData());
                }
            }
    );

    private final ActivityResultLauncher<Intent> editResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String imagePath = result.getData().getStringExtra("image_path");
                    if (imagePath != null) {
                        originalWarpedMat = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_GRAYSCALE);
                        updateImageThreshold();
                    }
                }
            }
    );

    private final ActivityResultLauncher<Intent> galleryResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    handleProcessingResult(result.getData());
                }
            }
    );

    private void handleProcessingResult(Intent data) {
        String imagePath = data.getStringExtra("image_path");
        int gridSize = data.getIntExtra("grid_size", 9);

        if (imagePath != null) {
            detectedGridSize = gridSize;
            Mat src = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_GRAYSCALE);
            if (src.empty()) return;

            float scaleFactor = 2.0f;
            if (gridSize == 16) scaleFactor = 3.2f;
            else if (gridSize == 25) scaleFactor = 4.0f;

            int newSize = (int) (src.cols() * scaleFactor);
            Mat resized = new Mat();
            Imgproc.resize(src, resized, new Size(newSize, newSize), 0, 0, Imgproc.INTER_CUBIC);

            if (originalWarpedMat != null) originalWarpedMat.release();
            originalWarpedMat = resized.clone();
            Imgcodecs.imwrite(imagePath, resized);
            src.release();
            resized.release();

            tvSudokuStatus.setText("Image Pre-processed");
            btnProcess.setVisibility(View.VISIBLE);
            btnEditImage.setVisibility(View.VISIBLE);
            btnCrop.setVisibility(View.VISIBLE);
            swMedianBlurToggle.setVisibility(View.VISIBLE);
            sbMedianBlurSlider.setVisibility(View.VISIBLE);
            swMorphologyToggle.setVisibility(View.VISIBLE);
            sbMorphologySlider.setVisibility(View.VISIBLE);
            swInvertToggle.setVisibility(View.VISIBLE);

            sbMedianBlurSlider.setProgress(0);
            sbMorphologySlider.setProgress(1);
            updateImageThreshold();
        }
    }

    private void updateImageThreshold() {
        if (originalWarpedMat == null || originalWarpedMat.empty()) return;

        Mat temp = originalWarpedMat.clone();
        Core.normalize(temp, temp, 0, 255, Core.NORM_MINMAX);

        if (swMedianBlurToggle.isChecked()) {
            int ksize = (sbMedianBlurSlider.getProgress() * 2) + 1;
            if (ksize > 1) {
                Mat blurred = new Mat();
                Imgproc.medianBlur(temp, blurred, ksize);
                temp.release(); temp = blurred;
            }
        }

        Mat smoothed = new Mat();
        Imgproc.bilateralFilter(temp, smoothed, 9, 75, 75);
        Mat sharpened = new Mat();
        sharpenMat(smoothed, sharpened);

        Mat processedMat = new Mat();
        if (swMorphologyToggle.isChecked()) {
            sharpened.copyTo(processedMat);
            int mSize = sbMorphologySlider.getProgress() + 1;
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(mSize, mSize));
            Imgproc.morphologyEx(processedMat, processedMat, Imgproc.MORPH_CLOSE, kernel);
            kernel.release();
        } else {
            sharpened.copyTo(processedMat);
        }

        if (swInvertToggle.isChecked()) {
            Core.bitwise_not(processedMat, processedMat);
        }

        Bitmap bmp = Bitmap.createBitmap(processedMat.cols(), processedMat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(processedMat, bmp);
        ivSudokuPreview.setImageBitmap(bmp);

        File file = new File(getExternalFilesDir(null), "captured_sudoku.jpg");
        Imgcodecs.imwrite(file.getAbsolutePath(), processedMat);

        temp.release();
        smoothed.release();
        sharpened.release();
        processedMat.release();
    }

    private void sharpenMat(Mat src, Mat dst) {
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(src, blurred, new Size(0, 0), 3);
        Core.addWeighted(src, 1.5, blurred, -0.5, 0, dst);
        blurred.release();
    }

    private final ActivityResultLauncher<PickVisualMediaRequest> pickMedia =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    Intent intent = new Intent(MainActivity.this, ProcessingActivity.class);
                    intent.setData(uri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    galleryResultLauncher.launch(intent);
                }
            });

    private void startCropIntent() {
        try {
            File file = new File(getExternalFilesDir(null), "captured_sudoku.jpg");
            if (!file.exists()) {
                Toast.makeText(this, "No image to crop", Toast.LENGTH_SHORT).show();
                return;
            }

            Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            
            Intent intent = new Intent("com.android.camera.action.CROP");
            intent.setDataAndType(uri, "image/*");
            intent.putExtra("crop", "true");
            intent.putExtra("aspectX", 1);
            intent.putExtra("aspectY", 1);
            intent.putExtra("outputX", 1000);
            intent.putExtra("outputY", 1000);
            intent.putExtra("scale", true);
            intent.putExtra("return-data", false);
            intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            cropResultLauncher.launch(intent);
        } catch (Exception e) {
            Log.e("MainActivity", "Crop intent failed", e);
            Toast.makeText(this, "Standard crop not supported on this device", Toast.LENGTH_SHORT).show();
        }
    }

    private final ActivityResultLauncher<Intent> cropResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    originalWarpedMat = Imgcodecs.imread(new File(getExternalFilesDir(null), "captured_sudoku.jpg").getAbsolutePath(), Imgcodecs.IMREAD_GRAYSCALE);
                    updateImageThreshold();
                }
            }
    );
}
