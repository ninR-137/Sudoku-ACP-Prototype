#include "sudokuant.h"
#include "sudokuantsystem.h"

void SudokuAnt::InitSolution(const Board &puzzle, int startCell )
{
	sol.Copy(puzzle);
	iCell = startCell;
	failCells = 0;
	if (roulette != nullptr)
	{
		delete[] roulette;
		delete[] rouletteVals;
	}
	roulette = new float[puzzle.GetNumUnits()];
	rouletteVals = new ValueSet[puzzle.GetNumUnits()];
}

void SudokuAnt::StepSolution()
{
	if (sol.GetCell(iCell).Empty())
	{
		failCells++;
	}
	else if ( !sol.GetCell(iCell).Fixed() )
	{
		// ACS pseudo-random proportional rule (Lloyd & Amos Eq. 1-2): q0 => greedy, else roulette
		ValueSet choice = ValueSet(sol.GetNumUnits(), 1);
		if (parent->random() < parent->Getq0())
		{
			// Greedy: argmax_k tau_ik
			ValueSet best;
			float maxPher = -1.0f;

			for (int i = 0; i < sol.GetNumUnits(); i++)
			{
				if (sol.GetCell(iCell).Contains(choice))
				{
					if (parent->Pher(iCell, i) > maxPher)
					{
						maxPher = parent->Pher(iCell, i);
						best = choice;
					}
				}
				choice <<= 1;
			}
			sol.SetCell(iCell, best);
			parent->LocalPheromoneUpdate(iCell, best.Index());  // Eq. 3: tau <- (1-xi)*tau + xi*tau0 (--xi)
		}
		else
		{
			// Roulette: p_ik = tau_ik / sum_j tau_ij
			float totPher = 0.0f;
			int numChoices = 0;
			for (int i = 0; i < sol.GetNumUnits(); i++)
			{
				if (sol.GetCell(iCell).Contains(choice))
				{
					roulette[numChoices] = totPher + parent->Pher(iCell, i);
					totPher = roulette[numChoices];
					rouletteVals[numChoices] = choice;
					++numChoices;
				}
				choice <<= 1;
			}
			float rouletteVal = totPher * parent->random();

			for (int i = 0; i < numChoices; i++)
			{
				if (roulette[i] > rouletteVal)
				{
					sol.SetCell(iCell, rouletteVals[i]);
					parent->LocalPheromoneUpdate(iCell, rouletteVals[i].Index());  // Eq. 3 (--xi)
					break;
				}
			}
		}
	}
	++iCell;
	if (iCell == sol.CellCount()) // wrap around
		iCell = 0;
}

