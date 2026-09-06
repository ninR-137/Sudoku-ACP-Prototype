#include <jni.h>
#include <string>
#include <vector>
#include "src/board.h"
#include "src/backtracksearch.h"
#include "src/sudokuantsystem.h"
#include "src/parallelsudokuantsystem.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_v2_1sudoku_1acp_1android_SolverActivity_solveSudokuNative(
        JNIEnv* env,
        jobject /* this */,
        jstring puzzlejStr,
        jint algorithm,
        jint threads) {

    const char* nativePuzzleStr = env->GetStringUTFChars(puzzlejStr, nullptr);
    std::string puzzleString(nativePuzzleStr);
    env->ReleaseStringUTFChars(puzzlejStr, nativePuzzleStr);

    Board board(puzzleString);
    SudokuSolver* solver = nullptr;

    // Algorithm mapping: 0=ACS, 1=Backtrack, 2=MCAS
    if (algorithm == 2) {
        // Parallel colonies (one per thread) with inter-colony communication
        solver = new ParallelSudokuAntSystem(threads, 25, 0.7f, 0.7f, 1.0f/board.CellCount(), 0.0075f, 0.5f);
    } else if (algorithm == 1) {
        // Exact backtracking baseline
        solver = new BacktrackSearch();
    } else {
        // Single-colony ant colony system
        solver = new SudokuAntSystem(25, 0.9f, 0.9f, 1.0f/board.CellCount(), 0.005f, 0.1f);
    }

    bool success = solver->Solve(board, 10.0f); // 10 second timeout
    std::string resultStr = "";

    if (success) {
        const Board& solution = solver->GetSolution();
        int numUnits = solution.GetNumUnits();

        for (int i = 0; i < solution.CellCount(); i++) {
            const ValueSet& cell = solution.GetCell(i);
            if (cell.Fixed()) {
                int val = cell.Index() + 1;

                // Handle different grid sizes (9x9 uses '1'-'9', 16x16 uses '0'-'f', 25x25 uses 'a'-'y')
                if (numUnits <= 9) {
                    resultStr += std::to_string(val);
                } else if (numUnits == 16) {
                    if (val <= 10) resultStr += (char)('0' + val - 1);
                    else resultStr += (char)('a' + val - 11);
                } else {
                    resultStr += (char)('a' + val - 1);
                }
            } else {
                resultStr += ".";
            }
        }
    }

    delete solver;
    return env->NewStringUTF(resultStr.c_str());
}
