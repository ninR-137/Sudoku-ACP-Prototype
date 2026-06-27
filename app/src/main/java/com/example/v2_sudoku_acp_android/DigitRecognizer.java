package com.example.v2_sudoku_acp_android;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.TransformToGrayscaleOp;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class DigitRecognizer {
    private static final String TAG = "DigitRecognizer";
    private static final String MODEL_FILE = "digit_model.tflite";
    private Interpreter tflite;
    private boolean isLoaded = false;

    public DigitRecognizer(Context context) {
        try {
            tflite = new Interpreter(loadModelFile(context));
            isLoaded = true;
            Log.i(TAG, "Model loaded successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error loading model: " + e.getMessage());
        }
    }

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(MODEL_FILE);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    public int recognize(Bitmap bitmap) {
        if (!isLoaded) return -1;

        // MNIST models expect 28x28 grayscale
        // Our extractDigit returns binary (0 or 255). 
        // We ensure it's normalized to 0.0-1.0 if the model is FLOAT32.
        ImageProcessor imageProcessor = new ImageProcessor.Builder()
                .add(new ResizeOp(28, 28, ResizeOp.ResizeMethod.BILINEAR))
                .add(new TransformToGrayscaleOp())
                .add(new NormalizeOp(0.0f, 255.0f))
                .build();

        TensorImage tensorImage = new TensorImage(org.tensorflow.lite.DataType.FLOAT32);
        tensorImage.load(bitmap);
        tensorImage = imageProcessor.process(tensorImage);

        float[][] output = new float[1][10];
        tflite.run(tensorImage.getBuffer(), output);

        int maxIdx = -1;
        float maxProb = 0.0f;
        for (int i = 0; i < 10; i++) {
            if (output[0][i] > maxProb) {
                maxProb = output[0][i];
                maxIdx = i;
            }
        }

        // 0 is often used for background or no digit in some Sudoku contexts, 
        // but in MNIST 0 is the digit 0.
        return maxIdx;
    }
}
