#pragma once
#include <vector>
#include <random>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include "antcolonyinterface.h"
#include "sudokuant.h"
#include "board.h"
#include "timer.h"
#include "sudokusolver.h"

// Forward declaration
class ParallelSudokuAntSystem;

// Sub-colony class representing one thread's ant colony
class SubColony : public IAntColony
{
private:
	int colonyId;
	int numAnts;
	float q0;
	float rho;        // ACS evaporation parameter (used for both standard and communication updates)
	float pher0;
	float xi;         // ACS local update: tau <- (1-xi)*tau + xi*tau0 (--xi)
	
	Board iterationBest;      // Best solution in current iteration (ΔT_ij^1 - local)
	Board bestSol;            // Best solution found so far (used in standard Algorithm 0 update)
	Board receivedIterationBest;  // Received iteration-best from ring topology (ΔT_ij^2)
	Board receivedBestSol;        // Received best-so-far from random topology (ΔT_ij^3)
	
	int iterationBestScore;   // Number of cells filled in iteration best
	int bestSolScore;         // Number of cells filled in best so far
	int receivedIterationBestScore;  // Score of received iteration-best
	int receivedBestSolScore;        // Score of received best-so-far
	
	std::vector<SudokuAnt*> antList;
	std::mt19937 randGen;
	std::uniform_real_distribution<float> randomDist;
	std::uniform_int_distribution<int> startPosDist;  // For ant starting positions (reused)
	
	float **pher; // pheromone matrix
	int numCells;
	int numUnits;
	
	// Temporary arrays for pheromone updates (allocated once, reused every iteration)
	float* contributions;
	bool* hasContribution;
	
	void InitPheromone(int numCells, int valuesPerCell);
	void ClearPheromone();
	
public:
	float PherAdd(int numCellsFixed);  // Made public for SA integration
	int currentIteration;     // Current iteration number (public for access by worker)
	float bestPher;           // Best pheromone value (for Algorithm 0 standard update)
	float bestEvap;           // Best pheromone evaporation parameter
	
	SubColony(int id, int numAnts, float q0, float rho, float pher0, float bestEvap, float xi = 0.5f);
	~SubColony();
	
	// Run one iteration of the ant colony
	void RunIteration(const Board& puzzle);
	
	// Standard Algorithm 0 global pheromone update - called every iteration
	void UpdatePheromone();
	
	// Communication-based three-source pheromone update - called after exchanges
	void UpdatePheromoneWithCommunication();
	
	// Get results
	const Board& GetIterationBest() const { return iterationBest; }
	const Board& GetBestSol() const { return bestSol; }
	int GetIterationBestScore() const { return iterationBestScore; }
	int GetBestSolScore() const { return bestSolScore; }
	int GetCurrentIteration() const { return currentIteration; }
	
	// Set solutions (for communication)
	void ReceiveIterationBest(const Board& solution);
	void ReceiveBestSol(const Board& solution);
	
	// Update best solution (for SA integration)
	void UpdateBestSolution(const Board& solution, int score);
	
	// Reset for new puzzle
	void Initialize(const Board& puzzle);
	
	// Helpers for ants
	inline float Getq0() { return q0; }
	inline float random() { return randomDist(randGen); }
	inline float Pher(int i, int j) { return pher[i][j]; }
	void LocalPheromoneUpdate(int iCell, int iChoice);
};

// Parallel Ant Colony System with multiple sub-colonies
class ParallelSudokuAntSystem : public SudokuSolver
{
private:
	int numSubColonies;
	float maxTime;  // Maximum time in seconds
	std::vector<SubColony*> subColonies;
	
	Board globalBest;
	int globalBestScore;
	int iterationsCompleted;
	bool communicationOccurred;
	float solTime;
	Timer solutionTimer;
	
	std::mt19937 masterRandGen;
	
	// Synchronization
	std::mutex commMutex;
	std::condition_variable commCV;
	std::atomic<int> barrier;
	std::atomic<bool> stopFlag;
	std::atomic<int> communicationSessions;  // Number of completed communication (barrier) sessions
	std::vector<double> idleTimePerThreadSeconds;  // Accumulated barrier idle time per thread
	
	// Communication helpers
	int CalculateInterval(int iteration);
	std::vector<int> GenerateMatchArray();
	void CommunicateRingTopology();
	void CommunicateRandomTopology(const std::vector<int>& matchArray);
	
	// Thread worker and helper methods
	void SubColonyWorker(int colonyId, const Board& puzzle);
	
	// Modular helper methods for SubColonyWorker
	bool CheckTimeout();
	void ReportProgress(int colonyId, int iteration, SubColony* colony, const Board& puzzle);
	bool CheckSolutionFound(SubColony* colony);
	void PerformBarrierSynchronization(int colonyId, const Board& puzzle);
	void ExecuteMasterThreadTasks(const Board& puzzle);
	void ExecuteWorkerThreadWait(std::unique_lock<std::mutex>& lock);
	
	int saFrequency;  // SA frequency (0 = disabled). Paper: sa_freq (Stodola et al.). Set from --safreq; SA every saFrequency iters.
	bool saAlwaysAccept; // If true, always accept SA solution regardless of quality
	double saTinit, saTmin, saCooling;  // SA schedule (--saTinit, --saTmin, --saCooling)
	int commEarly;
	int commLate;
	int commThreshold;
	bool streamProgress;  // If true, output best-so-far solution to stdout during solve (for webapp live display)
	bool communicationEnabled;  // If false (--comm 0), skip barriers and three-source update; threads are independent ACS colonies

public:
	ParallelSudokuAntSystem(int numSubColonies, int numAntsPerColony, 
	                        float q0, float rho, float pher0, float bestEvap, float xi = 0.5f, int safreq = 25, bool saAlwaysAccept = false,
	                        double saTinit = 5.75, double saTmin = 0.01, double saCooling = 0.995,
	                        int commEarly = 60, int commLate = 25, int commThreshold = 100, bool streamProgress = false,
	                        bool communicationEnabled = true);
	~ParallelSudokuAntSystem();
	
	virtual bool Solve(const Board& puzzle, float maxTime);
	virtual float GetSolutionTime() { return solTime; }
	virtual const Board& GetSolution() { return globalBest; }
	int GetIterationsCompleted() { return iterationsCompleted; }
	bool GetCommunicationOccurred() { return communicationOccurred; }
	bool IsCommunicationEnabled() const { return communicationEnabled; }
	int GetCommunicationSessions() const { return communicationSessions.load(); }
	const std::vector<double>& GetIdleTimePerThreadSeconds() const { return idleTimePerThreadSeconds; }
	void PrintColonyDetails();
};

