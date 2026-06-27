package com.example.v2_sudoku_acp_android;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import androidx.appcompat.app.AppCompatActivity;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

public class GalleryActivity extends AppCompatActivity {

    private QuadrilateralSelectionView quadSelector;
    private ImageView ivGalleryPreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);

        ivGalleryPreview = findViewById(R.id.ivGalleryPreview);
        quadSelector = findViewById(R.id.quadSelector);
        Button btnBack = findViewById(R.id.btnBack);
        Button btnFinishSelection = findViewById(R.id.btnFinishSelection);

        Uri imageUri = getIntent().getData();
        if (imageUri != null) {
            ivGalleryPreview.setImageURI(imageUri);
        }

        btnBack.setOnClickListener(v -> finish());
        btnFinishSelection.setOnClickListener(v -> finishSelection());
    }

    private void finishSelection() {
        if (ivGalleryPreview.getDrawable() == null) return;

        Bitmap bitmap = ((BitmapDrawable) ivGalleryPreview.getDrawable()).getBitmap();
        List<PointF> viewPoints = quadSelector.getPoints();

        // Map points from View to Bitmap
        Point[] bitmapPoints = mapPointsToBitmap(viewPoints, ivGalleryPreview, bitmap);

        // Perform Perspective Warp
        warpAndSave(bitmap, bitmapPoints);
    }

    private Point[] mapPointsToBitmap(List<PointF> viewPoints, ImageView imageView, Bitmap bitmap) {
        float[] values = new float[9];
        imageView.getImageMatrix().getValues(values);
        float fScaleX = values[Matrix.MSCALE_X];
        float fScaleY = values[Matrix.MSCALE_Y];
        float fTransX = values[Matrix.MTRANS_X];
        float fTransY = values[Matrix.MTRANS_Y];

        Point[] mapped = new Point[4];
        for (int i = 0; i < 4; i++) {
            float x = (viewPoints.get(i).x - fTransX) / fScaleX;
            float y = (viewPoints.get(i).y - fTransY) / fScaleY;
            mapped[i] = new Point(x, y);
        }
        return mapped;
    }

    private void warpAndSave(Bitmap bitmap, Point[] pts) {
        Mat src = new Mat();
        Utils.bitmapToMat(bitmap, src);

        // Sort points: top-left, top-right, bottom-right, bottom-left
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
        sorted[0] = pts[tl]; sorted[1] = pts[tr]; sorted[2] = pts[br]; sorted[3] = pts[bl];

        MatOfPoint2f srcPts = new MatOfPoint2f(sorted[0], sorted[1], sorted[2], sorted[3]);
        MatOfPoint2f dstPts = new MatOfPoint2f(new Point(0, 0), new Point(1000, 0), new Point(1000, 1000), new Point(0, 1000));
        
        Mat m = Imgproc.getPerspectiveTransform(srcPts, dstPts);
        Mat dst = new Mat();
        Imgproc.warpPerspective(src, dst, m, new Size(1000, 1000));

        // Save to file
        Bitmap resultBmp = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(dst, resultBmp);

        File file = new File(getExternalFilesDir(null), "warped_gallery_sudoku.jpg");
        try (FileOutputStream out = new FileOutputStream(file)) {
            resultBmp.compress(Bitmap.CompressFormat.JPEG, 100, out);
            
            Intent intent = new Intent(GalleryActivity.this, ProcessingActivity.class);
            intent.putExtra("image_path", file.getAbsolutePath());
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Log.e("Gallery", "Save failed", e);
        }

        src.release();
        dst.release();
        m.release();
        srcPts.release();
        dstPts.release();
    }
}
