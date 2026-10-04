package com.example.v2_sudoku_acp_android;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class SolverActivity extends AppCompatActivity {
    private static final String TAG = "SolverActivity";
    private GridLayout sudokuGrid;
    private EditText[] cellArray;
    private Bitmap[] debugCellImages;
    private int n;
    private String imagePath;
    private DigitRecognizer digitRecognizer;
    private SudokuOcr sudokuOcr;
    private boolean solved = false;
    private Button btnSolve;

    static {
        System.loadLibrary("native-lib");
    }

    public native String solveSudokuNative(String puzzleStr, int algorithm, int threads);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_solver);

        n = getIntent().getIntExtra("grid_size", 9);
        imagePath = getIntent().getStringExtra("image_path");
        if (n <= 0) n = 9;

        digitRecognizer = new DigitRecognizer(this);
        sudokuOcr = new SudokuOcr(digitRecognizer);

        sudokuGrid = findViewById(R.id.sudokuGrid);
        sudokuGrid.setRowCount(n);
        sudokuGrid.setColumnCount(n);

        cellArray = new EditText[n * n];
        debugCellImages = new Bitmap[n * n];
        createBoard();

        btnSolve = findViewById(R.id.btnSolve);
        btnSolve.setOnClickListener(v -> solveWithMCAS());
        btnSolve.setOnLongClickListener(v -> {
            exportOcrFixture();
            return true;
        });

        if (imagePath != null) {
            processFullBoard();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (digitRecognizer != null) {
            digitRecognizer.close();
        }
    }

    private void processFullBoard() {
        Mat fullImage = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_GRAYSCALE);
        if (fullImage.empty()) {
            Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show();
            return;
        }

        SudokuOcr.Result result = sudokuOcr.readBoard(fullImage, n);
        fullImage.release();

        for (int index = 0; index < n * n; index++) {
            debugCellImages[index] = result.cellImages[index];
            int value = result.values[index];

            if (value == SudokuOcr.EMPTY) {
                cellArray[index].setText("");
            } else if (value == SudokuOcr.UNREADABLE) {
                cellArray[index].setText("NA");
                cellArray[index].setTextColor(Color.RED);
            } else {
                cellArray[index].setText(String.valueOf(value));
                cellArray[index].setTextColor(Color.BLUE);
            }
        }

        markConflicts();
        Toast.makeText(this, "Scan Complete", Toast.LENGTH_SHORT).show();
    }

    // Flags recognized values that repeat in a row, column or box, since one of them must be misread
    private void markConflicts() {
        int blockSize = (int) Math.sqrt(n);
        if (blockSize == 0) return;

        for (int i = 0; i < n * n; i++) {
            String val = cellArray[i].getText().toString();
            if (val.isEmpty() || val.equals("NA")) continue;
            int r1 = i / n, c1 = i % n;

            for (int j = i + 1; j < n * n; j++) {
                if (!val.equals(cellArray[j].getText().toString())) continue;
                int r2 = j / n, c2 = j % n;

                boolean sameBox = (r1 / blockSize == r2 / blockSize) && (c1 / blockSize == c2 / blockSize);
                if (r1 == r2 || c1 == c2 || sameBox) {
                    cellArray[i].setTextColor(Color.RED);
                    cellArray[j].setTextColor(Color.RED);
                }
            }
        }
    }

    private void createBoard() {
        int blockSize = (int) Math.sqrt(n);
        if (blockSize == 0) blockSize = 3;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int cellSize = (screenWidth - 64) / n;

        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int index = r * n + c;
                EditText cell = new EditText(this);
                cell.setId(View.generateViewId());
                cellArray[index] = cell;

                GridLayout.LayoutParams params = new GridLayout.LayoutParams(GridLayout.spec(r), GridLayout.spec(c));
                params.width = cellSize;
                params.height = cellSize;
                params.setMargins(1, 1, 1, 1);
                cell.setLayoutParams(params);

                cell.setGravity(Gravity.CENTER);
                cell.setTextSize(n > 16 ? 12 : 18);
                cell.setTextColor(Color.BLACK);
                cell.setInputType(InputType.TYPE_CLASS_NUMBER);
                cell.setBackground(null);
                cell.setTag(index);

                cell.setOnLongClickListener(v -> {
                    int pos = (int) v.getTag();
                    if (debugCellImages != null && debugCellImages[pos] != null) {
                        showDebugCell(debugCellImages[pos], pos);
                        return true;
                    } else {
                        Toast.makeText(this, "No image captured for cell " + pos, Toast.LENGTH_SHORT).show();
                        return true;
                    }
                });

                if (n == 9) {
                    cell.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1)});
                }

                if (((r / blockSize) + (c / blockSize)) % 2 == 0) {
                    cell.setBackgroundColor(Color.parseColor("#E0E0E0"));
                } else {
                    cell.setBackgroundColor(Color.parseColor("#FFFFFF"));
                }
                sudokuGrid.addView(cell);
            }
        }
    }

    private void solveWithMCAS() {
        String puzzle = getBoardString();
        // algorithm 2 = MCAS, using 4 threads
        String solution = solveSudokuNative(puzzle, 2, 4);

        if (solution != null && !solution.isEmpty() && solution.length() == n * n) {
            for (int i = 0; i < solution.length(); i++) {
                char c = solution.charAt(i);
                if (c != '.') {
                    cellArray[i].setText(String.valueOf(c));
                    cellArray[i].setTextColor(Color.BLACK); // Solution in black
                }
            }
            solved = true;
            Toast.makeText(this, "Solved!", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "No solution found within timeout", Toast.LENGTH_SHORT).show();
        }
    }

    private String getBoardString() {
        StringBuilder sb = new StringBuilder();
        for (EditText et : cellArray) {
            String val = et.getText().toString();
            if (val.isEmpty()) {
                sb.append(".");
            } else {
                // The C++ Board constructor handles 9x9 (1-9), 16x16 (0-f), 25x25 (a-y)
                // We assume the user or OCR has entered valid characters for the size.
                sb.append(val.toLowerCase());
            }
        }
        return sb.toString();
    }

    // Saves the scanned image plus the board as currently shown (after manual corrections) as a
    // ground-truth pair for OcrAccuracyTest. Copy the pair into app/src/androidTest/assets/ocr_fixtures/.
    private void exportOcrFixture() {
        if (imagePath == null) {
            Toast.makeText(this, "No scanned image to export", Toast.LENGTH_SHORT).show();
            return;
        }
        if (solved) {
            Toast.makeText(this, "Export before solving: the board must show only the clues", Toast.LENGTH_LONG).show();
            return;
        }

        StringBuilder grid = new StringBuilder();
        for (int i = 0; i < cellArray.length; i++) {
            String val = cellArray[i].getText().toString().trim();
            if (val.equals("NA")) {
                Toast.makeText(this, "Correct the NA cells first", Toast.LENGTH_SHORT).show();
                return;
            }
            grid.append(val.isEmpty() ? "." : val);
            grid.append((i + 1) % n == 0 ? "\n" : " ");
        }

        File dir = getExternalFilesDir("ocr_fixtures");
        String name = "board_" + n + "x" + n + "_" + System.currentTimeMillis();
        try (InputStream in = new FileInputStream(imagePath);
             OutputStream imageOut = new FileOutputStream(new File(dir, name + ".jpg"));
             OutputStream gridOut = new FileOutputStream(new File(dir, name + ".txt"))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                imageOut.write(buffer, 0, read);
            }
            gridOut.write(grid.toString().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "Fixture saved: " + name, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Fixture export failed", e);
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showDebugCell(Bitmap bitmap, int index) {
        ImageView imageView = new ImageView(this);
        imageView.setImageBitmap(bitmap);
        imageView.setAdjustViewBounds(true);
        imageView.setPadding(32, 32, 32, 32);

        int r = index / n;
        int c = index % n;

        new AlertDialog.Builder(this)
                .setTitle("Cell Debug [" + r + "," + c + "]")
                .setView(imageView)
                .setPositiveButton("Save to Gallery", (dialog, which) -> saveBitmapToGallery(bitmap, "cell_" + r + "_" + c))
                .setNegativeButton("Close", null)
                .show();
    }

    private void saveBitmapToGallery(Bitmap bitmap, String name) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SudokuDebug");

        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri != null) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                    Toast.makeText(this, "Saved to Gallery", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Failed to open output stream", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Failed to save: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }
}
