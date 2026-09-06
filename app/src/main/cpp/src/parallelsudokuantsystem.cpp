/*******************************************************************************
 * PARALLEL ANT COLONY SYSTEM FOR SUDOKU - ALGORITHM 2
 * Per-colony ACS core: Lloyd & Amos, IEEE Trans. on Games (2021). See ACO_PAPER_VERIFICATION.md.
 * Parallelization (ring + random match, three-source update, adaptive interval): Yang et al.,
 * "RMACO: a randomly matched parallel ant colony optimization," World Wide Web (2016). See RMACO_PARALLEL_ADAPTATION.md.
 *
 * Multiple sub-colonies in parallel threads; each has its own pheromone matrix and ants.
 * - Ring topology: iteration-best to (i+1) mod n
 * - Random topology: best-so-far via match-array (random permutation)
 * - Three-source pheromone: Delta_tau^1 (local) + Delta_tau^2 (ring) + Delta_tau^3 (random)
 * - Adaptive exchange: interval 60 when iter < 100, else 25 (defaults; override with --commEarly/--commLate/--commThreshold)
 ******************************************************************************/

#include "parallelsudokuantsystem.h"
#include "simulatedannealing.h"
#include <iostream>
#include <algorithm>
#include <numeric>
#include <chrono>
#include <unordered_set>

// ============================================================================
// SUBCOLONY CLASS - Represents one independent ant colony in a thread
// ============================================================================

// ----------------------------------------------------------------------------
// Constructor: Initialize a sub-colony with its own parameters
// ----------------------------------------------------------------------------
SubColony::SubColony(int id, int numAnts, float q0, float rho, float pher0, float bestEvap, float xi)
	: colonyId(id), numAnts(numAnts), q0(q0), rho(rho), pher0(pher0), bestEvap(bestEvap), xi(xi), bestPher(0.0f),
	  iterationBestScore(0), bestSolScore(0), receivedIterationBestScore(0), receivedBestSolScore(0),
	  currentIteration(0), pher(nullptr), numCells(0), numUnits(0),
	  contributions(nullptr), hasContribution(nullptr)
{
	// Initialize random number generator with unique seed per colony
	randomDist = std::uniform_real_distribution<float>(0.0f, 1.0f);
	std::random_device rd;
	randGen = std::mt19937(rd() + id); // Different seed ensures diversity
}

SubColony::~SubColony()
{
	for (auto a : antList)
		delete a;
	if (pher != nullptr)
		ClearPheromone();
	if (contributions != nullptr)
		delete[] contributions;
	if (hasContribution != nullptr)
		delete[] hasContribution;
}

// ----------------------------------------------------------------------------
// Initialize: Prepare sub-colony for a new puzzle
// Called once before starting the parallel algorithm
// ----------------------------------------------------------------------------
void SubColony::Initialize(const Board& puzzle)
{
	numCells = puzzle.CellCount();
	numUnits = puzzle.GetNumUnits();
	
	// Setup random distribution for ant starting positions
	startPosDist = std::uniform_int_distribution<int>(0, numCells - 1);
	
	// === ANT POPULATION SETUP ===
	// Clear old ants if any
	for (auto a : antList)
		delete a;
	antList.clear();
	
	// Create new ant population
	for (int i = 0; i < numAnts; i++)
		antList.push_back(new SudokuAnt(this));
	
	// === PHEROMONE MATRIX INITIALIZATION ===
	InitPheromone(numCells, numUnits);
	
	// Allocate temporary arrays for pheromone updates (performance optimization)
	// These arrays are reused every iteration to avoid repeated allocations
	if (contributions != nullptr)
		delete[] contributions;
	if (hasContribution != nullptr)
		delete[] hasContribution;
	contributions = new float[numUnits];
	hasContribution = new bool[numUnits];
	
	// === SOLUTION TRACKING INITIALIZATION ===
	iterationBest.Copy(puzzle);
	bestSol.Copy(puzzle);
	receivedIterationBest.Copy(puzzle);   // From ring topology neighbor
	receivedBestSol.Copy(puzzle);         // From random topology partner
	
	iterationBestScore = puzzle.FixedCellCount();
	bestSolScore = puzzle.FixedCellCount();
	receivedIterationBestScore = 0;
	receivedBestSolScore = 0;
	currentIteration = 0;
	bestPher = 0.0f;  // Reset best pheromone value
}

void SubColony::InitPheromone(int nNumCells, int valuesPerCell)
{
	if (pher != nullptr)
		ClearPheromone();
	
	numCells = nNumCells;
	pher = new float*[numCells];
	for (int i = 0; i < numCells; i++)
	{
		pher[i] = new float[valuesPerCell];
		for (int j = 0; j < valuesPerCell; j++)
			pher[i][j] = pher0;
	}
}

void SubColony::ClearPheromone()
{
	for (int i = 0; i < numCells; i++)
		delete[] pher[i];
	delete[] pher;
	pher = nullptr;
}

float SubColony::PherAdd(int cellsFilled)
{
	return numCells / (float)(numCells - cellsFilled);
}

// ============================================================================
// STANDARD ALGORITHM 0 GLOBAL PHEROMONE UPDATE
// ============================================================================
// This is the standard ACS global update that happens EVERY iteration.
// Updates only the edges in the best-so-far solution.
//
// EQUATION: τ_ij(t+1) = (1-ρ)·τ_ij(t) + ρ·Δτ_best
//           where ρ = 0.9 (standard ACS parameter)
// ============================================================================
void SubColony::UpdatePheromone()
{
	for (int i = 0; i < numCells; i++)
	{
		if (bestSol.GetCell(i).Fixed())
		{
			pher[i][bestSol.GetCell(i).Index()] = pher[i][bestSol.GetCell(i).Index()] * (1.0f - rho) + rho * bestPher;
		}
	}
}

// ============================================================================
// THREE-SOURCE PHEROMONE UPDATE (Yang et al. RMACO Eq. 5)
// ============================================================================
// Called ONLY after communication. τ_ij(t+1) = (1-ρ)·τ_ij(t) + ρ·Δτ_ij,
// Δτ_ij = Δτ_ij^1 + Δτ_ij^2 + Δτ_ij^3 (ring iteration-best, random best-so-far).
// SOURCE 1: local iteration-best; 2: received iteration-best (ring); 3: received best-so-far (match).
// Selective evaporation: only [cell,digit] pairs that receive deposits are updated.
// ============================================================================
void SubColony::UpdatePheromoneWithCommunication()
{
	// --- STEP 1: Calculate pheromone deposit amounts for each source ---
	float pherValue1 = (iterationBestScore > 0) ? PherAdd(iterationBestScore) : 0.0f;
	float pherValue2 = (receivedIterationBestScore > 0) ? PherAdd(receivedIterationBestScore) : 0.0f;
	float pherValue3 = (receivedBestSolScore > 0) ? PherAdd(receivedBestSolScore) : 0.0f;
	
	// --- STEP 2: Process each cell ---
	for (int i = 0; i < numCells; i++)
	{
		// Clear temporary arrays for this cell
		std::fill(contributions, contributions + numUnits, 0.0f);
		std::fill(hasContribution, hasContribution + numUnits, false);
		
		// --- STEP 3: Collect contributions from all three sources ---
		
		// Source 1: Local iteration-best solution
		if (iterationBestScore > 0 && iterationBest.GetCell(i).Fixed())
		{
			int digit = iterationBest.GetCell(i).Index();
			contributions[digit] += pherValue1;
			hasContribution[digit] = true;
		}
		
		// Source 2: Iteration-best from ring topology neighbor
		if (receivedIterationBestScore > 0 && receivedIterationBest.GetCell(i).Fixed())
		{
			int digit = receivedIterationBest.GetCell(i).Index();
			contributions[digit] += pherValue2;
			hasContribution[digit] = true;
		}
		
		// Source 3: Best-so-far from random topology partner
		if (receivedBestSolScore > 0 && receivedBestSol.GetCell(i).Fixed())
		{
			int digit = receivedBestSol.GetCell(i).Index();
			contributions[digit] += pherValue3;
			hasContribution[digit] = true;
		}
		
		// --- STEP 4: Apply evaporation + reinforcement ---
		// Only for [cell, digit] pairs that received contributions
		for (int j = 0; j < numUnits; j++)
		{
			if (hasContribution[j])
			{
				// Evaporate old pheromone, add new contribution
				// Using rho for communication update (multiply contributions by rho)
				pher[i][j] = pher[i][j] * (1.0f - rho) + rho * contributions[j];
			}
		}
	}
}

void SubColony::LocalPheromoneUpdate(int iCell, int iChoice)
{
	pher[iCell][iChoice] = pher[iCell][iChoice] * (1.0f - xi) + pher0 * xi;
}

// ----------------------------------------------------------------------------
// RunIteration: Execute one complete iteration of the ant colony
// ----------------------------------------------------------------------------
void SubColony::RunIteration(const Board& puzzle)
{
	// === PHASE 1: SOLUTION CONSTRUCTION ===
	// Start each ant on a random cell (for diversity)
	for (auto a : antList)
	{
		a->InitSolution(puzzle, startPosDist(randGen));
	}
	
	// Each ant constructs a solution by visiting all cells
	for (int i = 0; i < numCells; i++)
	{
		// All ants take one step (fill one cell)
		for (auto a : antList)
		{
			a->StepSolution();
		}
	}
	
	// === PHASE 2: SOLUTION EVALUATION ===
	// Find the best ant in this iteration
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
	
	// === PHASE 3: SOLUTION TRACKING ===
	// Update iteration-best (best in this iteration)
	iterationBest.Copy(antList[iBest]->GetSolution());
	iterationBestScore = bestVal;
	
	// Calculate pheromone value for this iteration's best
	float pherToAdd = PherAdd(bestVal);
	
	// Update best-so-far (best ever found by this colony)
	// EXACTLY like Algorithm 0: compare by pheromone value, update BOTH together
	if (pherToAdd > bestPher)
	{
		bestSol.Copy(iterationBest);
		bestSolScore = iterationBestScore;
		bestPher = pherToAdd;
	}
}

void SubColony::ReceiveIterationBest(const Board& solution)
{
	// Store received iteration-best from ring topology
	// This solution is ONLY used for the three-source pheromone update
	// It does NOT update our own bestSol (which stays independent, like Algorithm 0)
	receivedIterationBest.Copy(solution);
	receivedIterationBestScore = solution.FixedCellCount();
}

void SubColony::ReceiveBestSol(const Board& solution)
{
	// Store received best-so-far from random topology
	// This solution is ONLY used for the three-source pheromone update
	// It does NOT update our own bestSol (which stays independent, like Algorithm 0)
	receivedBestSol.Copy(solution);
	receivedBestSolScore = solution.FixedCellCount();
}

void SubColony::UpdateBestSolution(const Board& solution, int score)
{
	// Update best solution and recalculate pheromone value
	// Used by Simulated Annealing integration
	bestSol.Copy(solution);
	bestSolScore = score;
	float pherToAdd = PherAdd(score);
	if (pherToAdd > bestPher)
	{
		bestPher = pherToAdd;
	}
}

// ============================================================================
// PARALLEL ANT COLONY SYSTEM - Master Coordinator
// ============================================================================
// This class manages multiple sub-colonies running in parallel threads.
// It handles thread creation, synchronization, and communication coordination.
// ============================================================================

// ----------------------------------------------------------------------------
// Constructor: Create the parallel system with N sub-colonies
// ----------------------------------------------------------------------------
ParallelSudokuAntSystem::ParallelSudokuAntSystem(int nSubColonies, int numAntsPerColony,
	float q0, float rho, float pher0, float bestEvap, float xi, int safreq, bool saAlwaysAcceptFlag,
	double saTinit, double saTmin, double saCooling,
	int commEarlyVal, int commLateVal, int commThresholdVal, bool streamProgressFlag, bool communicationEnabledFlag)
	: numSubColonies(nSubColonies), maxTime(120.0f),
	  globalBestScore(0), iterationsCompleted(0), communicationOccurred(false), solTime(0.0f), barrier(0), stopFlag(false), communicationSessions(0),
	  saFrequency(safreq), saAlwaysAccept(saAlwaysAcceptFlag), saTinit(saTinit), saTmin(saTmin), saCooling(saCooling),
	  commEarly(commEarlyVal), commLate(commLateVal), commThreshold(commThresholdVal), streamProgress(streamProgressFlag),
	  communicationEnabled(communicationEnabledFlag)
{
	// === INPUT VALIDATION ===
	// Ensure at least 1 sub-colony
	if (numSubColonies < 1)
	{
		std::cerr << "Warning: numSubColonies must be >= 1. Setting to 1." << std::endl;
		numSubColonies = 1;
	}
	idleTimePerThreadSeconds.assign(numSubColonies, 0.0);
	
	// Create N independent sub-colonies
	// Note: rho is used for both standard ACS global update and communication update
	for (int i = 0; i < numSubColonies; i++)
	{
		subColonies.push_back(new SubColony(i, numAntsPerColony, q0, rho, pher0, bestEvap, xi));
	}
	
	// Initialize master random generator (for random topology matching)
	std::random_device rd;
	masterRandGen = std::mt19937(rd());
}

ParallelSudokuAntSystem::~ParallelSudokuAntSystem()
{
	for (auto colony : subColonies)
		delete colony;
}

// Yang et al. RMACO: adaptive exchange cycle (Corollary 3). Before commThreshold iters: commEarly; after: commLate.
int ParallelSudokuAntSystem::CalculateInterval(int iteration)
{
	if (iteration < commThreshold)
		return commEarly;
	else
		return commLate;
}

// Yang et al. RMACO: match-array = random permutation of [0..n-1]; used for best-so-far exchange.
std::vector<int> ParallelSudokuAntSystem::GenerateMatchArray()
{
	std::vector<int> matchArray(numSubColonies);
	std::iota(matchArray.begin(), matchArray.end(), 0);
	std::random_device rd;
	std::mt19937 g(rd());
	std::shuffle(matchArray.begin(), matchArray.end(), g);
	return matchArray;
}

// ============================================================================
// COMMUNICATION TOPOLOGY 1: Ring (Yang et al. RMACO Fig. 1 – iteration-best)
// ============================================================================
// Colony i sends iteration-best to (i+1) mod n. Used for Delta_tau^2.
// Example: 0 → 1 → 2 → 3 → 0
// ============================================================================
void ParallelSudokuAntSystem::CommunicateRingTopology()
{
	std::vector<Board> iterationBests(numSubColonies);
	
	// --- STEP 1: Collect all iteration-best solutions ---
	for (int i = 0; i < numSubColonies; i++)
	{
		iterationBests[i].Copy(subColonies[i]->GetIterationBest());
	}
	
	// --- STEP 2: Send to next colony in ring ---
	for (int i = 0; i < numSubColonies; i++)
	{
		int nextId = (i + 1) % numSubColonies;  // Ring wraparound
		subColonies[nextId]->ReceiveIterationBest(iterationBests[i]);
	}
}

// ============================================================================
// COMMUNICATION TOPOLOGY 2: Randomly matched (Yang et al. RMACO Fig. 1 – best-so-far)
// ============================================================================
// Colony matchArray[i] receives best-so-far from matchArray[(i+n-1)%n]. Used for Delta_tau^3.
// Example: matchArray = [2,0,3,1] => 2←1, 0←2, 3←0, 1←3
// ============================================================================
void ParallelSudokuAntSystem::CommunicateRandomTopology(const std::vector<int>& matchArray)
{
	std::vector<Board> bestSols(numSubColonies);
	
	// --- STEP 1: Collect all best-so-far solutions ---
	for (int i = 0; i < numSubColonies; i++)
	{
		bestSols[i].Copy(subColonies[i]->GetBestSol());
	}
	
	// --- STEP 2: Send according to random matching ---
	for (int i = 0; i < numSubColonies; i++)
	{
		int colonyId = matchArray[i];
		int fromPos = (i + numSubColonies - 1) % numSubColonies;  // Previous in shuffled order
		int fromColonyId = matchArray[fromPos];
		
		subColonies[colonyId]->ReceiveBestSol(bestSols[fromColonyId]);
	}
}

// ============================================================================
// HELPER METHODS FOR MODULAR WORKER THREAD
// ============================================================================
// These methods break down the complex SubColonyWorker into manageable pieces,
// making the code easier to understand, test, and maintain.
// ============================================================================

// ----------------------------------------------------------------------------
// CountConflicts: Helper function to count constraint violations in a solution
// Returns the number of duplicate conflicts (row + column duplicates)
// Used for hybrid SA acceptance policy
// ----------------------------------------------------------------------------
static int CountConflicts(const Board& board)
{
	int conflicts = 0;
	int numUnits = board.GetNumUnits();
	
	// Count row duplicates
	for (int row = 0; row < numUnits; row++)
	{
		std::unordered_set<int> seen;
		for (int col = 0; col < numUnits; col++)
		{
			int idx = board.RowCell(row, col);
			const ValueSet& cell = board.GetCell(idx);
			if (cell.Fixed())
			{
				int val = cell.Index();
				if (seen.count(val))
					conflicts++; // duplicate value in row
				else
					seen.insert(val);
			}
		}
	}
	
	// Count column duplicates
	for (int col = 0; col < numUnits; col++)
	{
		std::unordered_set<int> seen;
		for (int row = 0; row < numUnits; row++)
		{
			int idx = board.ColCell(col, row);
			const ValueSet& cell = board.GetCell(idx);
			if (cell.Fixed())
			{
				int val = cell.Index();
				if (seen.count(val))
					conflicts++; // duplicate value in column
				else
					seen.insert(val);
			}
		}
	}
	
	return conflicts;
}

// ----------------------------------------------------------------------------
// CheckTimeout: Check if global timeout has been exceeded
// Returns true if timeout occurred and signals all threads to stop
// ----------------------------------------------------------------------------
bool ParallelSudokuAntSystem::CheckTimeout()
{
	if (solutionTimer.Elapsed() >= maxTime)
	{
		stopFlag.store(true);
		commCV.notify_all();  // Wake up waiting threads
		return true;
	}
	return false;
}

// ----------------------------------------------------------------------------
// ReportProgress: Display progress information (Colony 0 only)
// Shows the global best across ALL colonies, not just Colony 0
// ----------------------------------------------------------------------------
void ParallelSudokuAntSystem::ReportProgress(int colonyId, int iteration, SubColony* colony, const Board& puzzle)
{
	if (colonyId != 0 || iteration % 20 != 0)
		return;
	std::lock_guard<std::mutex> lock(commMutex);
	// Find global best across all colonies
	int globalBest = 0;
	int bestColony = -1;
	for (int i = 0; i < numSubColonies; i++)
	{
		int score = subColonies[i]->GetBestSolScore();
		if (score > globalBest)
		{
			globalBest = score;
			bestColony = i;
		}
	}
	if (!streamProgress)
	{
		std::cerr << "Progress: iteration " << iteration << " (Global best-so-far: "
		          << globalBest << "/" << puzzle.CellCount() << ")" << std::endl;
		return;
	}
	// Output best-so-far board to stdout for webapp live display (useNumbers=false so '.' for empty cells)
	if (bestColony >= 0 && globalBest > puzzle.FixedCellCount())
	{
		const Board& best = subColonies[bestColony]->GetBestSol();
		std::cout << "BestSoFar:" << std::endl;
		std::cout << best.AsString(false) << std::endl;
		std::cout << "---" << std::endl;
		std::cout.flush();
	}
}

// ----------------------------------------------------------------------------
// CheckSolutionFound: Check if colony found a complete solution
// Returns true if solution found and signals all threads to stop
// ----------------------------------------------------------------------------
bool ParallelSudokuAntSystem::CheckSolutionFound(SubColony* colony)
{
	if (colony->GetBestSolScore() == colony->GetBestSol().CellCount())
	{
		stopFlag.store(true);
		commCV.notify_all();  // Notify all threads to stop
		return true;
	}
	return false;
}

// ----------------------------------------------------------------------------
// ExecuteMasterThreadTasks: Tasks performed by the last thread to arrive
// This thread becomes the "master" and performs communication
// ----------------------------------------------------------------------------
void ParallelSudokuAntSystem::ExecuteMasterThreadTasks(const Board& puzzle)
{
	// Mark that communication occurred
	communicationOccurred = true;
	communicationSessions.fetch_add(1);  // Count this barrier communication session (once, by master thread)
	
	// Generate random matching for topology 2
	std::vector<int> matchArray = GenerateMatchArray();
	
	// --- COMMUNICATION TOPOLOGY 1: Ring (iteration-best) ---
	CommunicateRingTopology();
	
	// --- COMMUNICATION TOPOLOGY 2: Random (best-so-far) ---
	CommunicateRandomTopology(matchArray);
	
	// Check if any solution is complete
	for (int i = 0; i < numSubColonies; i++)
	{
		if (subColonies[i]->GetBestSolScore() == subColonies[i]->GetBestSol().CellCount())
		{
			stopFlag.store(true);
			break;
		}
	}
	
	// Reset barrier for next communication cycle
	barrier.store(0);
	
	// Release all waiting worker threads
	commCV.notify_all();
}

// ----------------------------------------------------------------------------
// ExecuteWorkerThreadWait: Worker threads wait for master to complete
// Uses timed wait to prevent deadlocks
// ----------------------------------------------------------------------------
void ParallelSudokuAntSystem::ExecuteWorkerThreadWait(std::unique_lock<std::mutex>& lock)
{
	auto predicate = [this]() { return barrier.load() == 0 || stopFlag.load(); };
	
	while (!predicate())
	{
		// Timed wait (prevents deadlock if something goes wrong)
		commCV.wait_for(lock, std::chrono::milliseconds(100), predicate);
		
		// Timeout check while waiting
		if (solutionTimer.Elapsed() >= maxTime && !stopFlag.load())
		{
			stopFlag.store(true);
			barrier.store(0);
			commCV.notify_all();
		}
	}
}

// ----------------------------------------------------------------------------
// PerformBarrierSynchronization: Coordinate all threads for communication
// Implements barrier pattern with master/worker roles
// ----------------------------------------------------------------------------
void ParallelSudokuAntSystem::PerformBarrierSynchronization(int colonyId, const Board& puzzle)
{
	auto idleStart = std::chrono::steady_clock::now();

	// Pre-check: Don't enter barrier if stop signal already set
	if (stopFlag.load())
		return;
	
	// === ENTER CRITICAL SECTION ===
	std::unique_lock<std::mutex> lock(commMutex);
	
	// Double-check stopFlag (race condition prevention)
	if (stopFlag.load())
	{
		barrier.store(0);
		commCV.notify_all();
		return;
	}
	
	// Increment barrier counter (atomic operation)
	int arrived = barrier.fetch_add(1) + 1;
	
	// === ROLE ASSIGNMENT ===
	if (arrived == numSubColonies)
	{
		// Last thread to arrive becomes MASTER
		ExecuteMasterThreadTasks(puzzle);
	}
	else
	{
		// Other threads become WORKERS and wait
		ExecuteWorkerThreadWait(lock);
	}

	auto idleEnd = std::chrono::steady_clock::now();
	if (colonyId >= 0 && colonyId < static_cast<int>(idleTimePerThreadSeconds.size()))
	{
		idleTimePerThreadSeconds[colonyId] += std::chrono::duration<double>(idleEnd - idleStart).count();
	}
	
	// === EXIT CRITICAL SECTION ===
	// Lock released automatically by unique_lock destructor
}

// ============================================================================
// SUBCOLONY WORKER THREAD - Main parallel execution loop
// ============================================================================
// This is the entry point for each thread. Each sub-colony runs independently
// in its own thread, periodically synchronizing for solution exchange.
//
// TERMINATION CONDITIONS:
// 1. Any colony finds a complete solution (all cells filled correctly)
// 2. Global timeout exceeded (default: 120 seconds)
//
// SYNCHRONIZATION:
// - Threads run independently most of the time (parallel execution)
// - Periodic barrier synchronization for solution communication
// - Master thread performs communication while others wait
//
// EXECUTION FLOW (per iteration):
// 1. Check timeout → 2. Run iteration → 3. Update pheromones → 
// 4. Report progress → 5. Check solution → 6. Communicate (periodic)
// ============================================================================
void ParallelSudokuAntSystem::SubColonyWorker(int colonyId, const Board& puzzle)
{
	SubColony* colony = subColonies[colonyId];
	colony->Initialize(puzzle);
	
	int iter = 0;
	
	// === MAIN ITERATION LOOP ===
	while (!stopFlag.load())
	{
		// --- STEP 1: Check Termination Conditions ---
		if (CheckTimeout())
			break;
		
		iter++;
		colony->currentIteration = iter;
		
		// --- STEP 2: Run Colony Iteration (Independent Parallel Work) ---
		colony->RunIteration(puzzle);
		
		// --- STEP 3: Pheromone Update (Mutually Exclusive) ---
		// Either standard Algorithm 0 update OR three-source communication update
		int interval = CalculateInterval(iter);
		if (communicationEnabled && iter % interval == 0)
		{
			// --- STEP 3a: Communication Phase (Periodic Barrier Synchronization) ---
			PerformBarrierSynchronization(colonyId, puzzle);
			
			// --- STEP 3b: Three-Source Communication Pheromone Update ---
			// Uses: local iteration-best + received iteration-best + received best-so-far
			colony->UpdatePheromoneWithCommunication();
			
			// Check stop flag after synchronization
			if (stopFlag.load())
				break;
		}
		else
		{
			// --- STEP 3c: Standard Algorithm 0 Global Pheromone Update ---
			// Uses: only local best-so-far
			colony->UpdatePheromone();
			
			// --- STEP 3d: Decay Best Pheromone (only on non-comm iterations, when bestPher is used) ---
			colony->bestPher *= (1.0f - colony->bestEvap);
		}
		
		// --- STEP 4: Apply Simulated Annealing (CP-adapted implementation, if enabled and at frequency interval) ---
		if (saFrequency > 0 && iter % saFrequency == 0 && iter != 0)
		{
			// Apply SA to best-so-far solution (same SA as single-colony and CP codebase)
			SudokuSA sa(colony->GetBestSol(), saTinit, saTmin, saCooling);
			int cost = sa.Anneal();
			Board saSolution = sa.GetSolution();
			
			int saScore = saSolution.FixedCellCount();
			int currentScore = colony->GetBestSolScore();
			
			bool shouldAccept = false;
			
			if (saAlwaysAccept)
			{
				// Command-line option forces acceptance regardless of quality
				shouldAccept = true;
			}
			else
			{
				// Hybrid acceptance policy (default):
				// 1. More cells filled (strict improvement), OR
				// 2. Same or slightly fewer cells (up to 2 cells less) BUT significantly fewer conflicts (≥5 conflicts reduced)
				int cellDiff = saScore - currentScore;
				
				if (cellDiff > 0)
				{
					// Case 1: Strict improvement (more cells)
					shouldAccept = true;
				}
				else if (cellDiff >= -2 && cellDiff <= 0)
				{
					// Case 2: Same or slightly fewer cells - check conflict reduction
					int currentConflicts = CountConflicts(colony->GetBestSol());
					int saConflicts = CountConflicts(saSolution);
					int conflictDiff = currentConflicts - saConflicts;
					
					// Accept if conflict reduction is significant (≥5 conflicts eliminated)
					if (conflictDiff >= 5)
					{
						shouldAccept = true;
					}
				}
			}
			
			if (shouldAccept)
			{
				// Update best solution using helper method
				colony->UpdateBestSolution(saSolution, saScore);
				
				// Check if SA found complete solution
				if (cost == 0 && saScore == puzzle.CellCount())
				{
					stopFlag.store(true);
					break;
				}
			}
		}
		
		// --- STEP 5: Report Progress (Colony 0 Only) ---
		ReportProgress(colonyId, iter, colony, puzzle);
		
		// --- STEP 6: Check if Solution Found ---
		if (CheckSolutionFound(colony))
			break;
	}
}

// ============================================================================
// MAIN SOLVE METHOD - Entry point for parallel algorithm
// ============================================================================
// This method orchestrates the entire parallel solving process:
// 1. Initializes synchronization structures
// 2. Spawns N worker threads (one per sub-colony)
// 3. Waits for completion (solution found or timeout)
// 4. Collects results from all threads
//
// EXECUTION FLOW:
// START → [Launch Threads] → [Parallel Execution] → [Join Threads] → [Collect Results] → END
// ============================================================================
bool ParallelSudokuAntSystem::Solve(const Board& puzzle, float timeLimit)
{
	// === INITIALIZATION ===
	maxTime = (timeLimit > 0) ? timeLimit : 120.0f;
	
	solutionTimer.Reset();
	stopFlag.store(false);   // Shared stop signal (atomic)
	barrier.store(0);        // Synchronization counter (atomic)
	communicationSessions.store(0);
	std::fill(idleTimePerThreadSeconds.begin(), idleTimePerThreadSeconds.end(), 0.0);
	
	globalBest.Copy(puzzle);
	globalBestScore = puzzle.FixedCellCount();
	
	// === THREAD CREATION ===
	// Launch N worker threads, one per sub-colony
	std::vector<std::thread> threads;
	for (int i = 0; i < numSubColonies; i++)
	{
		threads.emplace_back(&ParallelSudokuAntSystem::SubColonyWorker, this, i, std::ref(puzzle));
	}
	
	// === PARALLEL EXECUTION ===
	// Threads run independently, periodically synchronizing for communication
	// (Execution happens in SubColonyWorker method)
	
	// === THREAD SYNCHRONIZATION ===
	// Wait for all threads to complete (either solution found or timeout)
	for (auto& thread : threads)
	{
		thread.join();
	}
	
	// === RESULT COLLECTION ===
	// Find the best solution across all sub-colonies
	iterationsCompleted = 0;
	for (int i = 0; i < numSubColonies; i++)
	{
		// Find global best solution
		if (subColonies[i]->GetBestSolScore() > globalBestScore)
		{
			globalBest.Copy(subColonies[i]->GetBestSol());
			globalBestScore = subColonies[i]->GetBestSolScore();
		}
		
		// Track maximum iterations (for reporting)
		if (subColonies[i]->GetCurrentIteration() > iterationsCompleted)
		{
			iterationsCompleted = subColonies[i]->GetCurrentIteration();
		}
	}
	
	solTime = solutionTimer.Elapsed();
	
	// Return true if complete solution found
	bool solved = (globalBestScore == puzzle.CellCount());
	
	return solved;
}

void ParallelSudokuAntSystem::PrintColonyDetails()
{
	std::cout << "Sub-Colony Details:" << std::endl;
	for (int i = 0; i < numSubColonies; i++)
	{
		std::cout << "  Colony " << i << ": "
		          << "iter=" << subColonies[i]->GetCurrentIteration() << ", "
		          << "iter-best=" << subColonies[i]->GetIterationBestScore() << "/" << subColonies[i]->GetIterationBest().CellCount() << ", "
		          << "best-so-far=" << subColonies[i]->GetBestSolScore() << "/" << subColonies[i]->GetBestSol().CellCount() << ", "
		          << "idleTime=" << idleTimePerThreadSeconds[i] << "s"
		          << std::endl;
	}
}

