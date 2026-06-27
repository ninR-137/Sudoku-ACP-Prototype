package com.example.v2_sudoku_acp_android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Stack;

public class PaintView extends View {

    private Bitmap bitmap;
    private Canvas canvas;
    private final Paint paint = new Paint();
    private final Path path = new Path();
    private final Matrix drawMatrix = new Matrix();
    private final Matrix inverseMatrix = new Matrix();

    public enum Mode { PAINT, ZOOM }
    private Mode currentMode = Mode.PAINT;
    private int currentColor = Color.BLACK;

    private final Stack<Bitmap> undoStack = new Stack<>();
    private static final int MAX_UNDO_STEPS = 10;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public PaintView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint.setAntiAlias(true);
        paint.setStrokeWidth(20f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);

        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());
    }

    public void setImage(Bitmap bmp) {
        this.bitmap = bmp.copy(Bitmap.Config.ARGB_8888, true);
        this.canvas = new Canvas(this.bitmap);
        undoStack.clear();
        invalidate();
    }

    public Bitmap getBitmap() {
        return bitmap;
    }

    public void setMode(Mode mode) {
        this.currentMode = mode;
    }

    public void setColor(int color) {
        this.currentColor = color;
        paint.setColor(color);
    }

    public void setStrokeWidth(float width) {
        paint.setStrokeWidth(width);
    }

    public void undo() {
        if (!undoStack.isEmpty()) {
            this.bitmap = undoStack.pop();
            this.canvas = new Canvas(this.bitmap);
            invalidate();
        }
    }

    private void saveUndoState() {
        if (bitmap != null) {
            if (undoStack.size() >= MAX_UNDO_STEPS) {
                undoStack.remove(0);
            }
            undoStack.push(bitmap.copy(bitmap.getConfig(), true));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null) {
            canvas.save();
            canvas.concat(drawMatrix);
            canvas.drawBitmap(bitmap, 0, 0, null);
            canvas.restore();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (currentMode == Mode.ZOOM) {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
        } else {
            handlePaintTouch(event);
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void handlePaintTouch(MotionEvent event) {
        drawMatrix.invert(inverseMatrix);
        float[] pts = {event.getX(), event.getY()};
        inverseMatrix.mapPoints(pts);
        float x = pts[0];
        float y = pts[1];

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                saveUndoState();
                path.reset();
                path.moveTo(x, y);
                break;
            case MotionEvent.ACTION_MOVE:
                path.lineTo(x, y);
                canvas.drawPath(path, paint);
                path.reset();
                path.moveTo(x, y);
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
                canvas.drawPath(path, paint);
                path.reset();
                invalidate();
                break;
        }
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            drawMatrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
            invalidate();
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            drawMatrix.postTranslate(-distanceX, -distanceY);
            invalidate();
            return true;
        }
    }
}
