#pragma once
#include "board.h"

class SudokuSA
{
	Board sol;	// current working solution
    Board bestSol;
    Board currentSol;
    int bestCost;
    double acceptanceProbability;
    double tInit_;
    double tMin_;
    double cooling_;

public:	
	SudokuSA(Board sol, double tInit = 1.5, double tMin = 0.01, double cooling = 0.995);
    int Anneal();
    int ComputeCost();
    void FillEmptyCells();
    Board GetSolution(){return sol;}
private:
    int TryRandomSwap(int oldCost);
    void CleanDuplicates();
    int LocalConflicts(int idx);
    
}; 

