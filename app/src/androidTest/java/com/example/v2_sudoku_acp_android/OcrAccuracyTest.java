package com.example.v2_sudoku_acp_android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.AssetManager;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Runs SudokuOcr over every fixture in androidTest/assets/ocr_fixtures and reports cell accuracy.
 *
 * A fixture is an image as SolverActivity receives it (warped, square, grayscale) plus a .txt of
 * the same name holding the true grid: one row per line, values separated by spaces, "." for an
 * empty cell. Long-pressing the Solve button in the app exports such a pair.
 *
 * To add new test data:
 *
 * Scan the puzzle as usual until you reach the solver screen with the grid.
 * Fix every wrong cell by hand so the grid shows exactly the printed clues: clear false digits, type in missed ones and replace any red NA.
 * Long-press the Solve with MCAS button. A message "Fixture saved: board_9x9_…" confirms it.
 *
 * Then,
 * 
 * 1. Open terminal in the project root directory
 * 2. Enter command:
 * ~/Library/Android/sdk/platform-tools/adb pull /sdcard/Android/data/com.example.v2_sudoku_acp_android/files/ocr_fixtures app/src/androidTest/assets/
 */
@RunWith(AndroidJUnit4.class)
public class OcrAccuracyTest {
    private static final String TAG = "OcrAccuracy";
    private static final String FIXTURE_DIR = "ocr_fixtures";
    private static final String[] IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png"};

    // Fraction of all cells that must be read correctly. Raise this as the pipeline improves so
    // that a change which breaks previously working images fails the test.
    private static final double MIN_ACCURACY = 0.0;

    private AssetManager fixtures;
    private DigitRecognizer digitRecognizer;
    private SudokuOcr ocr;

    @Before
    public void setUp() {
        assertTrue("OpenCV failed to load", OpenCVLoader.initLocal());
        fixtures = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        digitRecognizer = new DigitRecognizer(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        ocr = new SudokuOcr(digitRecognizer);
    }

    @After
    public void tearDown() {
        digitRecognizer.close();
    }

    @Test
    public void cellAccuracyAcrossFixtures() throws IOException {
        List<String> names = new ArrayList<>();
        String[] files = fixtures.list(FIXTURE_DIR);
        if (files != null) {
            Arrays.sort(files);
            for (String file : files) {
                if (file.endsWith(".txt")) names.add(file.substring(0, file.length() - 4));
            }
        }
        assertFalse("No fixtures in androidTest/assets/" + FIXTURE_DIR, names.isEmpty());

        int totalCells = 0, totalCorrect = 0;
        int totalFalsePositive = 0, totalMissed = 0, totalMisread = 0, totalUnreadable = 0;
        StringBuilder report = new StringBuilder();

        for (String name : names) {
            int[] expected = readGrid(name);
            int n = (int) Math.round(Math.sqrt(expected.length));

            Mat board = readImage(name);
            SudokuOcr.Result result = ocr.readBoard(board, n);
            board.release();

            int correct = 0, falsePositive = 0, missed = 0, misread = 0, unreadable = 0;
            StringBuilder errors = new StringBuilder();

            for (int i = 0; i < expected.length; i++) {
                int want = expected[i];
                int got = result.values[i];
                if (want == got) {
                    correct++;
                    continue;
                }

                String kind;
                if (want == SudokuOcr.EMPTY) {
                    falsePositive++;
                    kind = "false positive";
                } else if (got == SudokuOcr.EMPTY) {
                    missed++;
                    kind = "missed";
                } else if (got == SudokuOcr.UNREADABLE) {
                    unreadable++;
                    kind = "unreadable";
                } else {
                    misread++;
                    kind = "misread";
                }
                errors.append(String.format(Locale.US, "    [row %d, col %d] expected %s, got %s (%s)%n",
                        i / n + 1, i % n + 1, describe(want), describe(got), kind));
            }

            report.append(String.format(Locale.US,
                    "%s (%dx%d): %d/%d correct (%.1f%%) | false positives %d, missed %d, misread %d, unreadable %d%n",
                    name, n, n, correct, expected.length, 100.0 * correct / expected.length,
                    falsePositive, missed, misread, unreadable));
            report.append(errors);

            totalCells += expected.length;
            totalCorrect += correct;
            totalFalsePositive += falsePositive;
            totalMissed += missed;
            totalMisread += misread;
            totalUnreadable += unreadable;
        }

        double accuracy = (double) totalCorrect / totalCells;
        report.append(String.format(Locale.US,
                "TOTAL over %d image(s): %d/%d correct (%.2f%%) | false positives %d, missed %d, misread %d, unreadable %d",
                names.size(), totalCorrect, totalCells, 100.0 * accuracy,
                totalFalsePositive, totalMissed, totalMisread, totalUnreadable));

        for (String line : report.toString().split("\n")) {
            Log.i(TAG, line);
        }

        assertTrue("Cell accuracy below " + MIN_ACCURACY + "\n" + report, accuracy >= MIN_ACCURACY);
    }

    private static String describe(int value) {
        if (value == SudokuOcr.EMPTY) return "empty";
        if (value == SudokuOcr.UNREADABLE) return "NA";
        return String.valueOf(value);
    }

    private int[] readGrid(String name) throws IOException {
        String text = new String(readAsset(name + ".txt"), StandardCharsets.UTF_8).trim();
        String[] tokens = text.split("\\s+");
        int n = (int) Math.round(Math.sqrt(tokens.length));
        assertEquals(name + ".txt does not describe a square grid", n * n, tokens.length);

        int[] grid = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            grid[i] = tokens[i].equals(".") ? SudokuOcr.EMPTY : Integer.parseInt(tokens[i]);
        }
        return grid;
    }

    private Mat readImage(String name) throws IOException {
        for (String extension : IMAGE_EXTENSIONS) {
            byte[] bytes;
            try {
                bytes = readAsset(name + extension);
            } catch (IOException e) {
                continue;
            }
            MatOfByte encoded = new MatOfByte(bytes);
            Mat image = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE);
            encoded.release();
            assertFalse("Could not decode " + name + extension, image.empty());
            return image;
        }
        throw new IOException("No image found for fixture " + name);
    }

    private byte[] readAsset(String file) throws IOException {
        try (InputStream in = fixtures.open(FIXTURE_DIR + "/" + file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
