#pragma once
#include <vector>
#include <random>
#include "antcolonyinterface.h"
#include "sudokuant.h"
#include "board.h"
#include "timer.h"
#include "sudokusolver.h"

class SudokuAntSystem : public SudokuSolver, public IAntColony
{
	int numAnts;
	float q0;
	float rho;
	float pher0;
	float bestEvap;
	float xi;  // ACS local update (Lloyd & Amos Eq. 3): tau <- (1-xi)*tau + xi*tau0
	int saFrequency;   // SA frequency (0 = disabled). Paper: sa_freq (Stodola et al.). Set from --safreq; SA every saFrequency iters.
	bool saAlwaysAccept;  // If true, always accept SA result (CP-like); else accept only when improvement or cost 0
	double saTinit, saTmin, saCooling;  // SA schedule: initial temp, stopping temp, cooling rate (--saTinit, --saTmin, --saCooling)
	Board bestSol;
	float bestPher;
	int bestVal;
	int iterationsCompleted;
	Timer solutionTimer;
	float solTime;  // Initialized to 0.0f at construction (default float initialization)

	std::vector<SudokuAnt*> antList;
	std::mt19937 randGen; 
	std::uniform_real_distribution<float> randomDist;

	float **pher; // pheromone matrix
	int numCells;
	void InitPheromone(int numCells, int valuesPerCell);
	void ClearPheromone();
	void UpdatePheromone();
	float PherAdd(int numCellsFixed);

public:
	SudokuAntSystem(int numAnts, float q0, float rho, float pher0, float bestEvap, float xi = 0.1f, int safreq = 0, bool saAlwaysAcceptFlag = false,
	                double saTinit = 1.5, double saTmin = 0.01, double saCooling = 0.995) : 
		numAnts(numAnts), q0(q0), rho(rho), pher0(pher0), bestEvap(bestEvap), xi(xi), saFrequency(safreq), saAlwaysAccept(saAlwaysAcceptFlag),
		saTinit(saTinit), saTmin(saTmin), saCooling(saCooling), iterationsCompleted(0)
	{
		for ( int i = 0; i < numAnts; i++ )
			antList.push_back(new SudokuAnt(this));
		randomDist = std::uniform_real_distribution<float>(0.0f, 1.0f);
		std::random_device rd;
		randGen = std::mt19937(rd());
	}
	~SudokuAntSystem()
	{
		for (auto a : antList)
			delete a;
	}
	virtual bool Solve(const Board& puzzle, float maxTime );
	virtual float GetSolutionTime() { return solTime; }
	virtual const Board& GetSolution() { return bestSol; }
	int GetIterationsCompleted() { return iterationsCompleted; }
	// helpers for ants
	inline float Getq0() { return q0; }
	inline float random() { return randomDist(randGen); }
	inline float Pher(int i, int j) { return pher[i][j]; }
	void LocalPheromoneUpdate(int iCell, int iChoice);
};
