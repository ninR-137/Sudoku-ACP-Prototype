package com.example.v2_sudoku_acp_android;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Locale;

public class DigitRecognizer {
    private static final String TAG = "DigitRecognizer";
    private static final String MODEL_FILE = "mnist.tflite";
    private static final int INPUT_SIZE = 28;
    private static final int PIXEL_COUNT = INPUT_SIZE * INPUT_SIZE;
    private static final float CONFIDENCE_THRESHOLD = 0.60f;

    private final Context context;
    private Interpreter tflite;
    private boolean isLoaded = false;
    private int debugCounter = 0;

    public DigitRecognizer(Context context) {
        this.context = context.getApplicationContext();

        try {
            Interpreter.Options options = new Interpreter.Options();
            options.setUseXNNPACK(false);
            options.setNumThreads(2);

            tflite = new Interpreter(loadModelFile(this.context), options);
            isLoaded = true;
            Log.i(TAG, "Model loaded successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error loading model: " + e.getMessage(), e);
        }
    }

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        try (AssetFileDescriptor fileDescriptor = context.getAssets().openFd(MODEL_FILE);
             FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
             FileChannel fileChannel = inputStream.getChannel()) {

            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        }
    }

    public int recognize(Bitmap bitmap) {
        if (!isLoaded || bitmap == null) {
            Log.w(TAG, "recognize() skipped: model not loaded or bitmap is null");
            return -1;
        }

        if (bitmap.getWidth() != INPUT_SIZE || bitmap.getHeight() != INPUT_SIZE) {
            Log.w(TAG, "Unexpected bitmap size: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            return -1;
        }

        int sampleId = debugCounter++;

        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(4 * PIXEL_COUNT);
        inputBuffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[PIXEL_COUNT];
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        float min = Float.MAX_VALUE;
        float max = Float.MIN_VALUE;

        for (int pixel : pixels) {
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;

            float gray = (r + g + b) / 3.0f;
            float normalized = gray / 255.0f;

            min = Math.min(min, normalized);
            max = Math.max(max, normalized);
            inputBuffer.putFloat(normalized);
        }

        float[][] output = new float[1][10];
        tflite.run(inputBuffer, output);

        int maxIdx = -1;
        float maxProb = -1.0f;
        StringBuilder scores = new StringBuilder("Scores for sample ")
                .append(sampleId)
                .append(": ");

        for (int i = 0; i < 10; i++) {
            float prob = output[0][i];
            scores.append(i).append("=").append(String.format(Locale.US, "%.4f", prob));
            if (i < 9) scores.append(", ");
            if (prob > maxProb) {
                maxProb = prob;
                maxIdx = i;
            }
        }

        Log.d(TAG, scores.toString());
        Log.d(TAG,
                "Prediction sample=" + sampleId +
                        ", predicted digit=" + maxIdx +
                        ", confidence=" + String.format(Locale.US, "%.4f", maxProb) +
                        ", inputRange=[" + String.format(Locale.US, "%.4f", min) + ", " + String.format(Locale.US, "%.4f", max) + "]"
        );

        if (maxProb < CONFIDENCE_THRESHOLD) {
            return -1;
        }

        return maxIdx;
    }

    public void close() {
        if (tflite != null) {
            tflite.close();
            tflite = null;
        }
        isLoaded = false;
    }
}
