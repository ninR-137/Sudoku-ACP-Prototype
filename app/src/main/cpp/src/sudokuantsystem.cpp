// Single-colony ACS for Sudoku. Algorithm follows Lloyd & Amos, "Solving Sudoku with
// Ant Colony Optimization," IEEE Trans. on Games (2021). See ACO_PAPER_VERIFICATION.md.
#include "sudokuantsystem.h"
#include "simulatedannealing.h"
#include <iostream>

void SudokuAntSystem::InitPheromone(int nNumCells, int valuesPerCell )
{
	numCells = nNumCells;
	pher = new float*[numCells];
	for (int i = 0; i < numCells; i++)
	{
		pher[i] = new float[valuesPerCell];
		for (int j = 0; j < valuesPerCell; j++)
			pher[i][j] = pher0;
	}
}

void SudokuAntSystem::ClearPheromone()
{
	for (int i = 0; i < numCells; i++)
		delete[] pher[i];
	delete[] pher;
}

// Delta-tau for best solution: c/(c-f) (Lloyd & Amos Eq. 5)
float SudokuAntSystem::PherAdd( int cellsFilled)
{
	return numCells / (float)(numCells - cellsFilled);
}

void SudokuAntSystem::UpdatePheromone()
{
	for (int i = 0; i < numCells; i++)
	{
		if (bestSol.GetCell(i).Fixed())
		{
			pher[i][bestSol.GetCell(i).Index()] = pher[i][bestSol.GetCell(i).Index()] * (1.0f - rho) + rho*bestPher;
		}
	}
}

// ACS local update (Lloyd & Amos Eq. 3): tau_is <- (1-xi)*tau_is + xi*tau0 (--xi, default 0.1)
void SudokuAntSystem::LocalPheromoneUpdate(int iCell, int iChoice)
{
	pher[iCell][iChoice] = pher[iCell][iChoice] * (1.0f - xi) + pher0 * xi;
}

bool SudokuAntSystem::Solve(const Board& puzzle, float maxTime )
{
	// Initialize first (may take time)
	int iter = 0;
	bool solved = false;
	bestPher = 0.0f;
	int curBestAnt = 0;
	iterationsCompleted = 0;
	InitPheromone( puzzle.CellCount(), puzzle.GetNumUnits() );
	
	// Reset timer AFTER initialization to exclude setup overhead
	solutionTimer.Reset();
	solTime = 0.0f;  // Initialize solution time
	
	while (!solved)
	{
		// start each ant on a different square
		std::uniform_int_distribution<int> dist(0, puzzle.CellCount()-1);
		for (auto a : antList)
		{
			a->InitSolution(puzzle, dist(randGen));
		}
		// fill cells one at a time
		for (int i = 0; i < puzzle.CellCount(); i++)
		{
			// step each ant in turn
			for (auto a : antList)
			{
				a->StepSolution();
			}
		}
		// update pheromone
		int iBest = 0;
		int bestVal = 0;
		for (unsigned int i = 0; i < antList.size(); i++)
		{
			if (antList[i]->NumCellsFilled() > bestVal)
			{
				bestVal = antList[i]->NumCellsFilled();
				iBest = i;
			}
		}
		float pherToAdd = PherAdd(bestVal);

		if (pherToAdd > bestPher)
		{
			// new best
			bestSol.Copy(antList[iBest]->GetSolution());
			bestPher = pherToAdd;
			curBestAnt = bestVal;
			if (bestVal == numCells)
			{
				solved = true;
				// Capture time immediately when solved, before any other operations
				solTime = solutionTimer.Elapsed();
				++iter;  // Increment iteration count before exiting
				break;  // Exit loop immediately to avoid extra work
			}
		}
		
		// Apply Simulated Annealing (CP-adapted implementation) if enabled and at frequency interval
		if (!solved && saFrequency > 0 && iter % saFrequency == 0 && iter != 0)
		{
			SudokuSA sa(bestSol, saTinit, saTmin, saCooling);
			int cost = sa.Anneal();
			Board saSolution = sa.GetSolution();
			int saScore = saSolution.FixedCellCount();
			
			bool shouldAccept = false;
			if (saAlwaysAccept)
			{
				// Optional always-accept (CP-like): --saAccept 1
				shouldAccept = true;
			}
			else
			{
				// Default: accept only when improvement or equal with cost 0 (preserves effectiveness)
				shouldAccept = (saScore > bestSol.FixedCellCount() || (saScore == bestSol.FixedCellCount() && cost == 0));
			}
			if (shouldAccept)
			{
				bestSol.Copy(saSolution);
				if (cost == 0 && saScore == numCells)
				{
					solved = true;
					solTime = solutionTimer.Elapsed();  // Capture time immediately when solved
					++iter;  // Increment iteration count before exiting
					break;  // Exit loop immediately to avoid extra work
				}
			}
		}
		
		// Only update pheromone and increment iteration if not solved
		if (!solved)
		{
			UpdatePheromone();  // Eq. 6: only best-so-far; no global evaporation elsewhere
			bestPher *= (1.0f - bestEvap);  // BVE Eq. 7: Delta_tau_best decay (evap = rhoBVE)
			++iter;
		}
		// check timer every 100 iterations
		if ((iter % 100) == 0)
		{
			float elapsed = solutionTimer.Elapsed();
			if ( elapsed > maxTime)
			{
				solTime = elapsed;  // Set time when timing out
				break;
			}
		}
	}
	// If we exit the loop without solving, ensure solTime is set
	if (!solved && solTime == 0.0f)
	{
		solTime = solutionTimer.Elapsed();
	}
	// If solved, ensure solTime is set (in case it wasn't set in the break statements)
	if (solved && solTime == 0.0f)
	{
		solTime = solutionTimer.Elapsed();
	}
	iterationsCompleted = iter;
	ClearPheromone();
	return solved;
	
}

