package com.example.v2_sudoku_acp_android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class QuadrilateralSelectionView extends View {

    private final Paint pointPaint = new Paint();
    private final Paint linePaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final List<PointF> points = new ArrayList<>();
    private int selectedPointIndex = -1;
    private static final float POINT_RADIUS = 30f;
    private static final float TOUCH_TOLERANCE = 60f;
    private int gridSize = 0; // 0 means no internal grid

    public QuadrilateralSelectionView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        pointPaint.setColor(Color.RED);
        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setAntiAlias(true);

        linePaint.setColor(Color.GREEN);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(8f);
        linePaint.setAntiAlias(true);

        gridPaint.setColor(Color.YELLOW);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(3f);
        gridPaint.setAlpha(180);
        gridPaint.setAntiAlias(true);

        // Initial 4 points forming a square in the center
        points.add(new PointF(200, 200));
        points.add(new PointF(600, 200));
        points.add(new PointF(600, 600));
        points.add(new PointF(200, 600));
    }

    public void setGridSize(int size) {
        this.gridSize = size;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (points.size() == 4) {
            PointF tl = points.get(0);
            PointF tr = points.get(1);
            PointF br = points.get(2);
            PointF bl = points.get(3);

            // Draw internal grid lines
            if (gridSize > 0) {
                for (int i = 1; i < gridSize; i++) {
                    float fraction = (float) i / gridSize;

                    // Vertical lines
                    float topX = tl.x + (tr.x - tl.x) * fraction;
                    float topY = tl.y + (tr.y - tl.y) * fraction;
                    float botX = bl.x + (br.x - bl.x) * fraction;
                    float botY = bl.y + (br.y - bl.y) * fraction;
                    canvas.drawLine(topX, topY, botX, botY, gridPaint);

                    // Horizontal lines
                    float leftX = tl.x + (bl.x - tl.x) * fraction;
                    float leftY = tl.y + (bl.y - tl.y) * fraction;
                    float rightX = tr.x + (br.x - tr.x) * fraction;
                    float rightY = tr.y + (br.y - tr.y) * fraction;
                    canvas.drawLine(leftX, leftY, rightX, rightY, gridPaint);
                }
            }

            // Draw outer lines
            for (int i = 0; i < 4; i++) {
                PointF p1 = points.get(i);
                PointF p2 = points.get((i + 1) % 4);
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, linePaint);
            }
        }

        // Draw corner points
        for (PointF point : points) {
            canvas.drawCircle(point.x, point.y, POINT_RADIUS, pointPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                selectedPointIndex = getNearestPointIndex(x, y);
                return selectedPointIndex != -1;

            case MotionEvent.ACTION_MOVE:
                if (selectedPointIndex != -1) {
                    points.get(selectedPointIndex).set(x, y);
                    invalidate();
                }
                break;

            case MotionEvent.ACTION_UP:
                if (selectedPointIndex == -1) {
                    performClick();
                }
                selectedPointIndex = -1;
                break;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private int getNearestPointIndex(float x, float y) {
        for (int i = 0; i < points.size(); i++) {
            PointF p = points.get(i);
            float dist = (float) Math.sqrt(Math.pow(p.x - x, 2) + Math.pow(p.y - y, 2));
            if (dist < TOUCH_TOLERANCE) {
                return i;
            }
        }
        return -1;
    }

    public List<PointF> getPoints() {
        return points;
    }
}
