package com.example.v2_sudoku_acp_android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.graphics.Color;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;

/** Drives SolverActivity without an image: types a board in, presses Solve, checks the outcome. */
@RunWith(AndroidJUnit4.class)
public class SolverActivityTest {
    // Native solver timeout is 10 s
    private static final long SOLVE_WAIT_MS = 15_000;

    // instances/9x9-database, first file
    private static final String PUZZLE_9 =
            ". . . . . . . 3 9\n" +
            ". . . . . 1 . . 5\n" +
            ". . 3 . 5 . 8 . .\n" +
            ". . 8 . 9 . . . 6\n" +
            ". 7 . . . 2 . . .\n" +
            "1 . . 4 . . . . .\n" +
            ". . 9 . 8 . . 5 .\n" +
            ". 2 . . . . 6 . .\n" +
            "4 . . 7 . . . . .";

    // instances/16x16-database/16x16_02203.txt
    private static final String PUZZLE_16 =
            "6 . . 1 9 2 . 10 . . . . . . 4 3\n" +
            "7 12 16 . 4 . . 13 . 8 . . . . . .\n" +
            "9 8 . . . 6 1 . 3 . . 11 10 . . .\n" +
            "14 . . . . . . . 4 16 . 15 . . . .\n" +
            ". . . 15 1 . . 2 . . 16 . 4 . 14 11\n" +
            "11 . . . . . 14 . . . 13 . . 8 7 .\n" +
            ". . . . . 11 . 7 . . 5 3 2 1 15 10\n" +
            "1 . . 2 . . . . 7 15 . 14 13 . . .\n" +
            ". . . . 12 . . . . . . . 5 15 11 16\n" +
            "2 . 12 13 . 9 16 11 . 6 . . . . . 4\n" +
            ". . . . . 10 . 5 . . 4 . 9 . . .\n" +
            "16 9 . . . 13 . 15 . . 7 . . . . .\n" +
            ". 3 4 . 2 . . . 15 . . 5 . . . 9\n" +
            "12 . 14 . 10 4 . . . . . . . 16 . .\n" +
            ". 5 11 . . . 9 12 . 2 8 . . . . 7\n" +
            ". 16 . . . . 3 . 9 . . . . 13 6 5";

    @Test
    public void solves9x9() {
        assertSolves(9, PUZZLE_9);
    }

    @Test
    public void solves16x16WithTwoDigitValues() {
        assertSolves(16, PUZZLE_16);
    }

    @Test
    public void rejectsRepeatedValue() {
        // Two 3s in the first row
        assertRejected(9, PUZZLE_9.replaceFirst("\\.", "3"), 0, 7);
    }

    @Test
    public void rejectsUnreadableCell() {
        assertRejected(9, PUZZLE_9.replaceFirst("\\.", "NA"), 0);
    }

    @Test
    public void rejectsOutOfRangeValue() {
        assertRejected(9, PUZZLE_9.replaceFirst("\\.", "0"), 0);
    }

    private void assertSolves(int n, String puzzle) {
        String[] clues = puzzle.trim().split("\\s+");
        try (ActivityScenario<SolverActivity> scenario = launch(n)) {
            AtomicBoolean started = new AtomicBoolean();
            scenario.onActivity(activity -> {
                fill(activity, clues);
                solveButton(activity).performClick();
                // The solve must run in the background: the click returns with it still in progress
                started.set(!solveButton(activity).isEnabled());
            });
            assertTrue("Solve did not start", started.get());

            waitUntilIdle(scenario);

            scenario.onActivity(activity -> {
                int[] values = new int[n * n];
                for (int i = 0; i < values.length; i++) {
                    String text = cell(activity, i).getText().toString();
                    assertFalse("Cell " + i + " left empty", text.isEmpty());
                    values[i] = Integer.parseInt(text);
                    if (!clues[i].equals(".")) {
                        assertEquals("Clue " + i + " changed", Integer.parseInt(clues[i]), values[i]);
                    }
                }
                assertValidSolution(n, values);
            });
        }
    }

    private void assertRejected(int n, String puzzle, int... redCells) {
        String[] clues = puzzle.trim().split("\\s+");
        String[] shown = new String[clues.length];
        try (ActivityScenario<SolverActivity> scenario = launch(n)) {
            scenario.onActivity(activity -> {
                fill(activity, clues);
                for (int i = 0; i < shown.length; i++) {
                    shown[i] = cell(activity, i).getText().toString();
                }
                solveButton(activity).performClick();

                assertTrue("Solve started on an invalid board", solveButton(activity).isEnabled());
                for (int index : redCells) {
                    assertEquals("Cell " + index + " not marked", Color.RED,
                            cell(activity, index).getCurrentTextColor());
                }
            });

            // Nothing may be filled in afterwards
            SystemClock.sleep(500);
            scenario.onActivity(activity -> {
                for (int i = 0; i < shown.length; i++) {
                    assertEquals("Cell " + i + " changed", shown[i], cell(activity, i).getText().toString());
                }
            });
        }
    }

    private static ActivityScenario<SolverActivity> launch(int n) {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), SolverActivity.class);
        intent.putExtra("grid_size", n);
        return ActivityScenario.launch(intent);
    }

    private static void waitUntilIdle(ActivityScenario<SolverActivity> scenario) {
        long deadline = SystemClock.uptimeMillis() + SOLVE_WAIT_MS;
        AtomicBoolean idle = new AtomicBoolean();
        while (!idle.get() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100);
            scenario.onActivity(activity -> idle.set(solveButton(activity).isEnabled()));
        }
        assertTrue("Solve did not finish in time", idle.get());
    }

    private static void fill(SolverActivity activity, String[] clues) {
        for (int i = 0; i < clues.length; i++) {
            cell(activity, i).setText(clues[i].equals(".") ? "" : clues[i]);
        }
    }

    private static EditText cell(SolverActivity activity, int index) {
        GridLayout grid = activity.findViewById(R.id.sudokuGrid);
        return (EditText) grid.getChildAt(index);
    }

    private static Button solveButton(SolverActivity activity) {
        return activity.findViewById(R.id.btnSolve);
    }

    private static void assertValidSolution(int n, int[] values) {
        int block = (int) Math.sqrt(n);
        for (int unit = 0; unit < n; unit++) {
            boolean[] row = new boolean[n + 1], col = new boolean[n + 1], box = new boolean[n + 1];
            for (int k = 0; k < n; k++) {
                int r = values[unit * n + k];
                int c = values[k * n + unit];
                int b = values[(unit / block * block + k / block) * n + unit % block * block + k % block];
                assertTrue("Value out of range", r >= 1 && r <= n && c >= 1 && c <= n && b >= 1 && b <= n);
                assertFalse("Repeat in row " + unit, row[r]);
                assertFalse("Repeat in column " + unit, col[c]);
                assertFalse("Repeat in box " + unit, box[b]);
                row[r] = col[c] = box[b] = true;
            }
        }
    }
}
