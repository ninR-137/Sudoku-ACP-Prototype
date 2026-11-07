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
import org.opencv.core.MatOfInt;
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
    // Boards are searched for on a copy reduced to this size, which averages out sensor noise
    // (important in low light) and keeps the threshold behaving the same for any photo size
    private static final int DETECTION_SIZE = 1000;
    // Longest side photos are reduced to on load (about 12 MP). Camera apps can save 50-200 MP
    // photos, too large to display (Android refuses bitmaps over about 100 MB) or process quickly.
    private static final int MAX_PHOTO_SIDE = 4032;
    // Side of the on-screen preview the user selects the grid size and filters on
    private static final int PREVIEW_SIZE = 1000;
    // Smallest board accepted, as a fraction of the image
    private static final double MIN_BOARD_AREA_FRACTION = 0.016;
    // Larger outlines are the edge of the image itself, not a board (boards measured up to 89%)
    private static final double MAX_BOARD_AREA_FRACTION = 0.95;
    // Brightness range (max - min gray level) below which the image is contrast-stretched. Real
    // dim phone photos measured 125-182; an image darkened to a quarter measured about 64.
    private static final double STRETCH_BELOW_RANGE = 100;
    // Threshold offset; the camera's live detection uses the same value at its default slider setting
    private static final int THRESHOLD_C = 5;
    // A candidate inside another that is this many times larger is a box or cell of that board
    private static final double NESTED_PART_RATIO = 8.0;
    // A candidate inside another of at least this relative size is the inner edge of the same border
    private static final double NESTED_DUPLICATE_RATIO = 0.8;

    private ImageView ivProcessedImage;
    private TextView tvProcessingStatus;
    private ProgressBar progressBar;
    private View viewSelectionOverlay;
    private LinearLayout llSliders, llGridSizePicker;
    private HorizontalScrollView hsvProcessButtons;
    private Button btnFinish;

    private Mat originalFullMat; 
    private Mat warpedBaseMat;   
    // Selected board corners in the full photo (top-left, top-right, bottom-right, bottom-left)
    private Point[] boardCorners;
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

            if (loadedMat != null && !loadedMat.empty()
                    && Math.max(loadedMat.cols(), loadedMat.rows()) > MAX_PHOTO_SIDE) {
                double shrink = MAX_PHOTO_SIDE / (double) Math.max(loadedMat.cols(), loadedMat.rows());
                Mat reduced = new Mat();
                Imgproc.resize(loadedMat, reduced, new Size(), shrink, shrink, Imgproc.INTER_AREA);
                loadedMat.release();
                loadedMat = reduced;
            }

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
            List<MatOfPoint2f> found = findBoardCandidates(gray);
            Log.i("Processing", "Board detection: image " + gray.cols() + "x" + gray.rows()
                    + " from " + (getIntent().getStringExtra("image_path") != null ? "camera" : "gallery")
                    + ", mean brightness " + Math.round(Core.mean(gray).val[0])
                    + ", " + found.size() + " candidate(s)");
            gray.release();

            for (MatOfPoint2f rect : detectedRectangles) rect.release();
            detectedRectangles.clear();
            detectedRectangles.addAll(found);
            runOnUiThread(this::drawDetectionUI);
        });
    }

    // Four-cornered outlines that could be the board, largest first, in the input's coordinates.
    // Uses the same steps as the camera's live detection: reduced copy, image-relative threshold,
    // gap closing, and nested outlines (so a board inside a page border or a noise blob is found).
    static List<MatOfPoint2f> findBoardCandidates(Mat gray) {
        double scale = Math.min(1.0, DETECTION_SIZE / (double) Math.max(gray.cols(), gray.rows()));
        Mat small = new Mat();
        Imgproc.resize(gray, small, new Size(), scale, scale, Imgproc.INTER_AREA);
        // Very flat images (a narrow brightness range, as in an underexposed photo) are stretched to the
        // full range, otherwise lines and paper differ by less than the threshold offset. Normal
        // photos, including dim ones the camera has brightened, are left alone: stretching them
        // amplifies grain and shading along with the lines and breaks the board outline.
        Core.MinMaxLocResult range = Core.minMaxLoc(small);
        if (range.maxVal - range.minVal < STRETCH_BELOW_RANGE) {
            Core.normalize(small, small, 0, 255, Core.NORM_MINMAX);
        }

        Mat binary = new Mat();
        int blockSize = Math.max(11, (small.cols() / 50) | 1);
        Imgproc.adaptiveThreshold(small, binary, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, blockSize, THRESHOLD_C);
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_CROSS, new Size(3, 3));
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel);
        kernel.release();

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);
        binary.release();
        hierarchy.release();

        double minArea = small.cols() * small.rows() * MIN_BOARD_AREA_FRACTION;
        double maxArea = small.cols() * small.rows() * MAX_BOARD_AREA_FRACTION;
        small.release();
        List<MatOfPoint2f> quads = new ArrayList<>();
        List<Double> areas = new ArrayList<>();
        for (MatOfPoint contour : contours) {
            double area = Imgproc.contourArea(contour);
            if (area > minArea && area < maxArea) {
                MatOfPoint2f c2f = new MatOfPoint2f(contour.toArray());
                MatOfPoint2f approx = new MatOfPoint2f();
                Imgproc.approxPolyDP(c2f, approx, 0.02 * Imgproc.arcLength(c2f, true), true);
                c2f.release();
                if (approx.total() == 4 && Imgproc.isContourConvex(new MatOfPoint(approx.toArray()))) {
                    quads.add(approx);
                    areas.add(Imgproc.contourArea(approx));
                } else {
                    approx.release();
                }
            }
            contour.release();
        }

        // Drop boxes and cells of a board, and inner-edge duplicates of a border
        List<MatOfPoint2f> result = new ArrayList<>();
        List<Double> resultAreas = new ArrayList<>();
        for (int i = 0; i < quads.size(); i++) {
            boolean keep = true;
            for (int j = 0; j < quads.size() && keep; j++) {
                if (i == j || areas.get(j) <= areas.get(i) || !isInside(quads.get(i), quads.get(j))) continue;
                double ratio = areas.get(i) / areas.get(j);
                if (ratio * NESTED_PART_RATIO < 1.0 || ratio >= NESTED_DUPLICATE_RATIO) keep = false;
            }
            if (keep) {
                int pos = 0;
                while (pos < resultAreas.size() && resultAreas.get(pos) >= areas.get(i)) pos++;
                result.add(pos, quads.get(i));
                resultAreas.add(pos, areas.get(i));
            } else {
                quads.get(i).release();
            }
        }

        // Back to the input's coordinates
        for (MatOfPoint2f quad : result) {
            Point[] pts = quad.toArray();
            for (Point p : pts) {
                p.x /= scale;
                p.y /= scale;
            }
            quad.fromArray(pts);
        }
        return result;
    }

    private static boolean isInside(MatOfPoint2f inner, MatOfPoint2f outer) {
        for (Point p : inner.toArray()) {
            if (Imgproc.pointPolygonTest(outer, p, false) < 0) return false;
        }
        return true;
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

        // Candidates are sorted largest first, so the last hit is the smallest one containing the
        // tap: a board rather than the page around it
        int selectedIdx = -1;
        for (int i = 0; i < detectedRectangles.size(); i++) {
            if (Imgproc.pointPolygonTest(detectedRectangles.get(i), tapPoint, false) >= 0) {
                selectedIdx = i;
            }
        }

        if (selectedIdx != -1) performInitialWarp(detectedRectangles.get(selectedIdx));
        else Toast.makeText(this, "Tap a highlighted board", Toast.LENGTH_SHORT).show();
    }

    private void performInitialWarp(MatOfPoint2f corners) {
        progressBar.setVisibility(View.VISIBLE);
        tvProcessingStatus.setText("Warping...");
        executor.execute(() -> {
            boardCorners = sortCorners(corners.toArray());
            // Low-resolution preview for choosing the grid size and filters; the saved board is
            // warped again from the full photo at its final size in saveAndReturn
            warpedBaseMat = warpBoard(originalFullMat, boardCorners, PREVIEW_SIZE);
            runOnUiThread(() -> {
                viewSelectionOverlay.setVisibility(View.GONE);
                llGridSizePicker.setVisibility(View.VISIBLE);
                hsvProcessButtons.setVisibility(View.VISIBLE);
                btnFinish.setVisibility(View.VISIBLE);
                tvProcessingStatus.setText("Select grid size and fine-tune");
                resetPipeline();
                // Preselect the size the camera detected, if any
                int detected = getIntent().getIntExtra("grid_size", 0);
                selectGridSize(detected == 16 || detected == 25 ? detected : 9); 
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
        Mat temp = applyFilters(warpedBaseMat, 1.0);

        if (currentMat != null) currentMat.release();
        currentMat = temp;
        
        Mat display = currentMat.clone();
        drawGridOnMat(display, selectedGridSize, 1.0);
        displayMatNoGrid(display);
        display.release();
    }

    // Applies the selected filters to a copy of base. Kernel sizes are set on the preview, so
    // scale (the image's size relative to the preview) keeps their effect the same on larger images.
    private Mat applyFilters(Mat base, double scale) {
        Mat temp = base.clone();

        if (isGray || isThresh || isCanny || isMedian) Imgproc.cvtColor(temp, temp, Imgproc.COLOR_RGB2GRAY);
        if (isMedian) { Mat t = new Mat(); Imgproc.medianBlur(temp, t, scaledOdd(medianKernel, scale)); temp.release(); temp = t; }
        if (isThresh) {
            Mat t = new Mat();
            Imgproc.adaptiveThreshold(temp, t, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, scaledOdd(11, scale), threshC);
            temp.release(); temp = t;
        }
        if (isMorph) {
            Mat t = new Mat();
            int size = Math.max(1, (int) Math.round(morphSize * scale));
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(size, size));
            Imgproc.morphologyEx(temp, t, Imgproc.MORPH_CLOSE, kernel);
            kernel.release(); temp.release(); temp = t;
        }
        if (isCanny) { Mat t = new Mat(); Imgproc.Canny(temp, t, cannyThresh1, cannyThresh2); temp.release(); temp = t; }
        if (isBlur) {
            Mat t = new Mat();
            int k = scaledOdd(blurKernel, scale);
            Imgproc.GaussianBlur(temp, t, new Size(k, k), 0);
            temp.release(); temp = t;
        }
        return temp;
    }

    private static int scaledOdd(int kernel, double scale) {
        int k = (int) Math.round(kernel * scale);
        return k % 2 == 0 ? k + 1 : Math.max(1, k);
    }

    // Final side of the saved board. These are the sizes the recognizer's pixel-based settings
    // were tuned on (cells of about 220, 200 and 160 px).
    static int boardSizeFor(int gridSize) {
        if (gridSize == 25) return 4000;
        if (gridSize == 16) return 3200;
        return 2000;
    }

    // Flattens the board with the given corners (top-left, top-right, bottom-right, bottom-left)
    // to a size x size image. warpPerspective does not average pixels when shrinking, so a photo
    // whose board is larger than the target is first reduced with area averaging.
    static Mat warpBoard(Mat src, Point[] corners, int size) {
        double longestSide = 0;
        for (int i = 0; i < 4; i++) {
            Point a = corners[i], b = corners[(i + 1) % 4];
            longestSide = Math.max(longestSide, Math.hypot(a.x - b.x, a.y - b.y));
        }

        Mat source = src;
        Point[] pts = new Point[4];
        for (int i = 0; i < 4; i++) pts[i] = corners[i].clone();
        double shrink = size / longestSide;
        if (shrink < 1.0) {
            source = new Mat();
            Imgproc.resize(src, source, new Size(), shrink, shrink, Imgproc.INTER_AREA);
            for (Point p : pts) {
                p.x *= shrink;
                p.y *= shrink;
            }
        }

        MatOfPoint2f srcPts = new MatOfPoint2f(pts);
        MatOfPoint2f dstPts = new MatOfPoint2f(new Point(0, 0), new Point(size, 0), new Point(size, size), new Point(0, size));
        Mat m = Imgproc.getPerspectiveTransform(srcPts, dstPts);
        Mat board = new Mat();
        Imgproc.warpPerspective(source, board, m, new Size(size, size), Imgproc.INTER_LINEAR);
        m.release(); srcPts.release(); dstPts.release();
        if (source != src) source.release();
        return board;
    }

    private void drawGridOnMat(Mat mat, int gridSize, double scale) {
        Scalar color = new Scalar(255, 255, 0); // Yellow
        if (mat.channels() == 1) color = new Scalar(255); // White for gray
        
        int thickness = Math.max(2, (int) Math.round(2 * scale));
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
        if (currentMat == null || boardCorners == null) return;
        progressBar.setVisibility(View.VISIBLE);
        tvProcessingStatus.setText("Saving...");
        btnFinish.setEnabled(false);
        final int gridSize = selectedGridSize;

        executor.execute(() -> {
            // Warp again from the full photo, straight to the final size, so the recognizer gets
            // the photo's real detail rather than an enlarged copy of the preview
            int size = boardSizeFor(gridSize);
            Mat board = warpBoard(originalFullMat, boardCorners, size);
            Mat returnMat = applyFilters(board, size / (double) PREVIEW_SIZE);
            board.release();

            // Burn grid into final image as requested
            drawGridOnMat(returnMat, gridSize, size / (double) PREVIEW_SIZE);
            if (returnMat.channels() > 1) Imgproc.cvtColor(returnMat, returnMat, Imgproc.COLOR_RGB2GRAY);

            // PNG is lossless, so no compression artifacts reach the recognizer
            File file = new File(getExternalFilesDir(null), "preprocessed_sudoku.png");
            MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_PNG_COMPRESSION, 1);
            boolean saved = Imgcodecs.imwrite(file.getAbsolutePath(), returnMat, params);
            params.release();
            returnMat.release();

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!saved) {
                    Log.e("Processing", "Save failed");
                    progressBar.setVisibility(View.GONE);
                    btnFinish.setEnabled(true);
                    Toast.makeText(this, "Could not save the board", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent resultIntent = new Intent();
                resultIntent.putExtra("image_path", file.getAbsolutePath());
                resultIntent.putExtra("grid_size", gridSize);
                setResult(RESULT_OK, resultIntent);
                finish();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy(); executor.shutdown();
        if (originalFullMat != null) originalFullMat.release();
        if (warpedBaseMat != null) warpedBaseMat.release();
        if (currentMat != null) currentMat.release();
    }
}
