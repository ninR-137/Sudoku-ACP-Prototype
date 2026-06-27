package com.example.v2_sudoku_acp_android;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProcessingActivity extends AppCompatActivity {

    private ImageView ivProcessedImage;
    private TextView tvProcessingStatus;
    private ProgressBar progressBar;
    private View viewSelectionOverlay;
    private LinearLayout llSliders, llGridSizePicker;
    private HorizontalScrollView hsvProcessButtons;
    private Button btnFinish;

    private Mat originalFullMat; 
    private Mat warpedBaseMat;   
    private Mat currentMat;      

    private List<MatOfPoint2f> detectedRectangles = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private boolean isGray = false, isThresh = false, isCanny = false, isBlur = false, isMedian = false, isMorph = false;
    private int threshC = 2, cannyThresh1 = 50, cannyThresh2 = 150, blurKernel = 5, medianKernel = 3, morphSize = 3;
    private int selectedGridSize = 9;

    private TextView tvSliderLabel1, tvSliderLabel2;
    private SeekBar sbParam1, sbParam2;
    private String activeTool = "";

    private Button btn9x9, btn16x16, btn25x25;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_processing);

        ivProcessedImage = findViewById(R.id.ivProcessedImage);
        tvProcessingStatus = findViewById(R.id.tvProcessingStatus);
        progressBar = findViewById(R.id.progressBar);
        viewSelectionOverlay = findViewById(R.id.viewSelectionOverlay);
        llSliders = findViewById(R.id.llSliders);
        hsvProcessButtons = findViewById(R.id.llProcessButtons);
        btnFinish = findViewById(R.id.btnFinishProcessing);
        llGridSizePicker = findViewById(R.id.llGridSizePicker);

        tvSliderLabel1 = findViewById(R.id.tvSliderLabel1);
        tvSliderLabel2 = findViewById(R.id.tvSliderLabel2);
        sbParam1 = findViewById(R.id.sbParam1);
        sbParam2 = findViewById(R.id.sbParam2);

        btn9x9 = findViewById(R.id.btn9x9);
        btn16x16 = findViewById(R.id.btn16x16);
        btn25x25 = findViewById(R.id.btn25x25);

        findViewById(R.id.btnBackToGallery).setOnClickListener(v -> finish());
        
        btn9x9.setOnClickListener(v -> selectGridSize(9));
        btn16x16.setOnClickListener(v -> selectGridSize(16));
        btn25x25.setOnClickListener(v -> selectGridSize(25));

        setupFilterButtons();

        loadImage();

        viewSelectionOverlay.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                v.performClick();
                handleTap(event.getX(), event.getY());
            }
            return true;
        });
        viewSelectionOverlay.setOnClickListener(v -> {});

        sbParam1.setOnSeekBarChangeListener(sliderListener);
        sbParam2.setOnSeekBarChangeListener(sliderListener);
        
        btnFinish.setOnClickListener(v -> saveAndReturn());
    }

    private void setupFilterButtons() {
        Button btnReset = findViewById(R.id.btnReset);
        Button btnGray = findViewById(R.id.btnGray);
        Button btnThresh = findViewById(R.id.btnThresh);
        Button btnCanny = findViewById(R.id.btnCanny);
        Button btnBlur = findViewById(R.id.btnBlur);
        Button btnMedian = findViewById(R.id.btnMedian);
        Button btnMorph = findViewById(R.id.btnMorph);

        btnReset.setOnClickListener(v -> resetPipeline());
        btnGray.setOnClickListener(v -> { isGray = !isGray; updateButtonState(btnGray, isGray); updatePipeline(); });
        btnThresh.setOnClickListener(v -> { isThresh = !isThresh; updateButtonState(btnThresh, isThresh); showThreshSliders(); updatePipeline(); });
        btnCanny.setOnClickListener(v -> { isCanny = !isCanny; updateButtonState(btnCanny, isCanny); showCannySliders(); updatePipeline(); });
        btnBlur.setOnClickListener(v -> { isBlur = !isBlur; updateButtonState(btnBlur, isBlur); showBlurSliders(); updatePipeline(); });
        btnMedian.setOnClickListener(v -> { isMedian = !isMedian; updateButtonState(btnMedian, isMedian); showMedianSliders(); updatePipeline(); });
        btnMorph.setOnClickListener(v -> { isMorph = !isMorph; updateButtonState(btnMorph, isMorph); showMorphSliders(); updatePipeline(); });
    }

    private void updateButtonState(Button btn, boolean active) {
        if (active) btn.setBackgroundColor(Color.LTGRAY);
        else btn.setBackgroundColor(Color.parseColor("#6200EE"));
    }

    private void loadImage() {
        progressBar.setVisibility(View.VISIBLE);
        tvProcessingStatus.setText("Loading image...");
        executor.execute(() -> {
            String imagePath = getIntent().getStringExtra("image_path");
            Uri imageUri = getIntent().getData();
            Mat loadedMat = null;
            try {
                if (imagePath != null) {
                    loadedMat = Imgcodecs.imread(imagePath);
                    Imgproc.cvtColor(loadedMat, loadedMat, Imgproc.COLOR_BGR2RGB);
                } else if (imageUri != null) {
                    InputStream is = getContentResolver().openInputStream(imageUri);
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    loadedMat = new Mat();
                    Utils.bitmapToMat(bmp, loadedMat);
                    Imgproc.cvtColor(loadedMat, loadedMat, Imgproc.COLOR_RGBA2RGB);
                }
            } catch (Exception e) { Log.e("Processing", "Load failed", e); }

            final Mat finalMat = loadedMat;
            runOnUiThread(() -> {
                if (finalMat != null && !finalMat.empty()) {
                    originalFullMat = finalMat;
                    detectRectangles();
                } else {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void detectRectangles() {
        tvProcessingStatus.setText("Pick your board...");
        executor.execute(() -> {
            Mat gray = new Mat();
            Imgproc.cvtColor(originalFullMat, gray, Imgproc.COLOR_RGB2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 0);
            Imgproc.adaptiveThreshold(gray, gray, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 11, 2);

            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(gray, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            for (MatOfPoint2f rect : detectedRectangles) rect.release();
            detectedRectangles.clear();
            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);
                if (area > 10000) {
                    MatOfPoint2f c2f = new MatOfPoint2f(contour.toArray());
                    double peri = Imgproc.arcLength(c2f, true);
                    MatOfPoint2f approx = new MatOfPoint2f();
                    Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true);
                    if (approx.total() == 4) detectedRectangles.add(approx);
                    else { approx.release(); c2f.release(); }
                }
            }
            gray.release(); hierarchy.release();
            runOnUiThread(this::drawDetectionUI);
        });
    }

    private void drawDetectionUI() {
        Mat canvas = originalFullMat.clone();
        for (int i = 0; i < detectedRectangles.size(); i++) {
            Imgproc.drawContours(canvas, Collections.singletonList(new MatOfPoint(detectedRectangles.get(i).toArray())), -1, new Scalar(255, 0, 0), 15);
            Point p = detectedRectangles.get(i).toArray()[0];
            Imgproc.putText(canvas, "#" + (i + 1), p, Imgproc.FONT_HERSHEY_SIMPLEX, 4.0, new Scalar(0, 255, 0), 10);
        }
        displayMatNoGrid(canvas);
        canvas.release();
        progressBar.setVisibility(View.GONE);
    }

    private void handleTap(float x, float y) {
        if (warpedBaseMat != null) return; 

        Matrix inverse = new Matrix();
        ivProcessedImage.getImageMatrix().invert(inverse);
        float[] pts = {x, y};
        inverse.mapPoints(pts);
        Point tapPoint = new Point(pts[0], pts[1]);

        int selectedIdx = -1;
        for (int i = 0; i < detectedRectangles.size(); i++) {
            if (Imgproc.pointPolygonTest(detectedRectangles.get(i), tapPoint, false) >= 0) {
                selectedIdx = i; break;
            }
        }

        if (selectedIdx != -1) performInitialWarp(detectedRectangles.get(selectedIdx));
        else Toast.makeText(this, "Tap a highlighted board", Toast.LENGTH_SHORT).show();
    }

    private void performInitialWarp(MatOfPoint2f corners) {
        progressBar.setVisibility(View.VISIBLE);
        tvProcessingStatus.setText("Warping...");
        executor.execute(() -> {
            Point[] pts = corners.toArray();
            Point[] sorted = sortCorners(pts);
            MatOfPoint2f srcPts = new MatOfPoint2f(sorted[0], sorted[1], sorted[2], sorted[3]);
            MatOfPoint2f dstPts = new MatOfPoint2f(new Point(0, 0), new Point(1000, 0), new Point(1000, 1000), new Point(0, 1000));
            Mat m = Imgproc.getPerspectiveTransform(srcPts, dstPts);
            warpedBaseMat = new Mat();
            Imgproc.warpPerspective(originalFullMat, warpedBaseMat, m, new Size(1000, 1000));
            
            m.release(); srcPts.release(); dstPts.release();
            runOnUiThread(() -> {
                viewSelectionOverlay.setVisibility(View.GONE);
                llGridSizePicker.setVisibility(View.VISIBLE);
                hsvProcessButtons.setVisibility(View.VISIBLE);
                btnFinish.setVisibility(View.VISIBLE);
                tvProcessingStatus.setText("Select grid size and fine-tune");
                resetPipeline();
                selectGridSize(9); 
                progressBar.setVisibility(View.GONE);
            });
        });
    }

    private void selectGridSize(int size) {
        selectedGridSize = size;
        updateButtonState(btn9x9, size == 9);
        updateButtonState(btn16x16, size == 16);
        updateButtonState(btn25x25, size == 25);
        updatePipeline();
    }

    private Point[] sortCorners(Point[] pts) {
        Point[] sorted = new Point[4];
        double[] sum = new double[4], diff = new double[4];
        for(int i=0; i<4; i++) { sum[i] = pts[i].x + pts[i].y; diff[i] = pts[i].x - pts[i].y; }
        int tl=0, br=0, tr=0, bl=0;
        for(int i=1; i<4; i++) {
            if (sum[i] < sum[tl]) tl = i; if (sum[i] > sum[br]) br = i;
            if (diff[i] > diff[tr]) tr = i; if (diff[i] < diff[bl]) bl = i;
        }
        sorted[0] = pts[tl]; sorted[1] = pts[tr]; sorted[2] = pts[br]; sorted[3] = pts[bl];
        return sorted;
    }

    private void resetPipeline() {
        isGray = isThresh = isCanny = isBlur = isMedian = isMorph = false;
        llSliders.setVisibility(View.GONE);
        updatePipeline();
    }

    private void updatePipeline() {
        if (warpedBaseMat == null) return;
        Mat temp = warpedBaseMat.clone();

        if (isGray || isThresh || isCanny || isMedian) Imgproc.cvtColor(temp, temp, Imgproc.COLOR_RGB2GRAY);
        if (isMedian) { Mat t = new Mat(); Imgproc.medianBlur(temp, t, medianKernel); temp.release(); temp = t; }
        if (isThresh) {
            Mat t = new Mat();
            Imgproc.adaptiveThreshold(temp, t, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 11, threshC);
            temp.release(); temp = t;
        }
        if (isMorph) {
            Mat t = new Mat();
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(morphSize, morphSize));
            Imgproc.morphologyEx(temp, t, Imgproc.MORPH_CLOSE, kernel);
            kernel.release(); temp.release(); temp = t;
        }
        if (isCanny) { Mat t = new Mat(); Imgproc.Canny(temp, t, cannyThresh1, cannyThresh2); temp.release(); temp = t; }
        if (isBlur) { Mat t = new Mat(); Imgproc.GaussianBlur(temp, t, new Size(blurKernel, blurKernel), 0); temp.release(); temp = t; }

        if (currentMat != null) currentMat.release();
        currentMat = temp;
        
        Mat display = currentMat.clone();
        drawGridOnMat(display, selectedGridSize);
        displayMatNoGrid(display);
        display.release();
    }

    private void drawGridOnMat(Mat mat, int gridSize) {
        Scalar color = new Scalar(255, 255, 0); // Yellow
        if (mat.channels() == 1) color = new Scalar(255); // White for gray
        
        int thickness = 2;
        int width = mat.cols();
        int height = mat.rows();
        
        for (int i = 0; i <= gridSize; i++) {
            int x = i * width / gridSize;
            Imgproc.line(mat, new Point(x, 0), new Point(x, height), color, thickness);
            int y = i * height / gridSize;
            Imgproc.line(mat, new Point(0, y), new Point(width, y), color, thickness);
        }
    }

    private void displayMatNoGrid(Mat mat) {
        Bitmap bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(mat, bmp);
        ivProcessedImage.setImageBitmap(bmp);
    }

    private final SeekBar.OnSeekBarChangeListener sliderListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (!fromUser) return;
            switch (activeTool) {
                case "THRESH": threshC = progress - 20; tvSliderLabel1.setText("Thresh C: " + threshC); break;
                case "CANNY": 
                    if (seekBar.getId() == R.id.sbParam1) cannyThresh1 = progress; else cannyThresh2 = progress;
                    tvSliderLabel1.setText("Canny T1: " + cannyThresh1); tvSliderLabel2.setText("Canny T2: " + cannyThresh2); break;
                case "BLUR": blurKernel = (progress * 2) + 1; tvSliderLabel1.setText("Blur: " + blurKernel); break;
                case "MEDIAN": medianKernel = (progress * 2) + 3; tvSliderLabel1.setText("Median: " + medianKernel); break;
                case "MORPH": morphSize = progress + 1; tvSliderLabel1.setText("Heal: " + morphSize); break;
            }
            updatePipeline();
        }
        @Override public void onStartTrackingTouch(SeekBar s) {} @Override public void onStopTrackingTouch(SeekBar s) {}
    };

    private void showThreshSliders() { 
        if (!isThresh) { llSliders.setVisibility(View.GONE); return; }
        activeTool = "THRESH"; llSliders.setVisibility(View.VISIBLE);
        tvSliderLabel2.setVisibility(View.GONE); sbParam2.setVisibility(View.GONE);
        tvSliderLabel1.setText("Threshold C: " + threshC); sbParam1.setMax(40); sbParam1.setProgress(threshC + 20);
    }
    private void showCannySliders() {
        if (!isCanny) { llSliders.setVisibility(View.GONE); return; }
        activeTool = "CANNY"; llSliders.setVisibility(View.VISIBLE);
        tvSliderLabel2.setVisibility(View.VISIBLE); sbParam2.setVisibility(View.VISIBLE);
        tvSliderLabel1.setText("Canny T1: " + cannyThresh1); tvSliderLabel2.setText("Canny T2: " + cannyThresh2);
        sbParam1.setMax(255); sbParam1.setProgress(cannyThresh1); sbParam2.setMax(255); sbParam2.setProgress(cannyThresh2);
    }
    private void showBlurSliders() {
        if (!isBlur) { llSliders.setVisibility(View.GONE); return; }
        activeTool = "BLUR"; llSliders.setVisibility(View.VISIBLE);
        tvSliderLabel2.setVisibility(View.GONE); sbParam2.setVisibility(View.GONE);
        tvSliderLabel1.setText("Blur: " + blurKernel); sbParam1.setMax(10); sbParam1.setProgress((blurKernel - 1) / 2);
    }
    private void showMedianSliders() {
        if (!isMedian) { llSliders.setVisibility(View.GONE); return; }
        activeTool = "MEDIAN"; llSliders.setVisibility(View.VISIBLE);
        tvSliderLabel2.setVisibility(View.GONE); sbParam2.setVisibility(View.GONE);
        tvSliderLabel1.setText("Median: " + medianKernel); sbParam1.setMax(10); sbParam1.setProgress((medianKernel - 3) / 2);
    }
    private void showMorphSliders() {
        if (!isMorph) { llSliders.setVisibility(View.GONE); return; }
        activeTool = "MORPH"; llSliders.setVisibility(View.VISIBLE);
        tvSliderLabel2.setVisibility(View.GONE); sbParam2.setVisibility(View.GONE);
        tvSliderLabel1.setText("Heal: " + morphSize); sbParam1.setMax(10); sbParam1.setProgress(morphSize - 1);
    }

    private void saveAndReturn() {
        if (currentMat == null) return;
        File file = new File(getExternalFilesDir(null), "preprocessed_sudoku.jpg");
        Mat returnMat = currentMat.clone();
        
        // Burn grid into final image as requested
        drawGridOnMat(returnMat, selectedGridSize);

        if (returnMat.channels() > 1) Imgproc.cvtColor(returnMat, returnMat, Imgproc.COLOR_RGB2GRAY);
        Bitmap bmp = Bitmap.createBitmap(returnMat.cols(), returnMat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(returnMat, bmp);
        try (FileOutputStream out = new FileOutputStream(file)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 100, out);
            Intent resultIntent = new Intent();
            resultIntent.putExtra("image_path", file.getAbsolutePath());
            resultIntent.putExtra("grid_size", selectedGridSize);
            setResult(RESULT_OK, resultIntent); finish();
        } catch (Exception e) { Log.e("Processing", "Save failed", e); }
        finally { returnMat.release(); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy(); executor.shutdown();
        if (originalFullMat != null) originalFullMat.release();
        if (warpedBaseMat != null) warpedBaseMat.release();
        if (currentMat != null) currentMat.release();
    }
}
