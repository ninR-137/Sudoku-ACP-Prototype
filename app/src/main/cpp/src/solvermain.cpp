#include "sudokuantsystem.h"
#include "parallelsudokuantsystem.h"
#include "sudokusolver.h"
#include "backtracksearch.h"
#include "board.h"
#include "arguments.h"
#include <iostream>
#include <iomanip>
#include <fstream>
#include <string>
using namespace std;

string ReadFile( string fileName )
{
	char *puzString;
	ifstream inFile;
	inFile.open(fileName);
	if ( inFile.is_open() )
	{
		int order, idum;
		inFile >> order;
		int numCells = order*order*order*order;
		inFile >> idum;
		puzString = new char[numCells+1];
		for (int i = 0; i < numCells; i++)
		{
			int val;
			inFile >> val;
			if (val == -1)
				puzString[i] = '.';
			else if (order == 3)
				puzString[i] = '1' + (val - 1);
			else if (order == 4)
				if (val < 11)
					puzString[i] = '0' + val - 1;
				else
					puzString[i] = 'a' + val - 11;
			else
				puzString[i] = 'a' + val - 1;
		}
		puzString[numCells] = 0;
		inFile.close();
		string retVal = string(puzString);
		delete [] puzString;
		return retVal;
	}
	else
	{
		cerr << "could not open file: " << fileName << endl;
		return string();
	}
}

int main( int argc, char *argv[] )
{
	// solve, then spit out 0 for success, 1 for fail, followed by time in seconds
	Arguments a( argc, argv );
	string puzzleString;
	if ( a.GetArg("blank", 0 ) && a.GetArg("order", 0 ))
	{
		int order = a.GetArg("order", 0 );
		if ( order != 0 )
			puzzleString = string(order*order*order*order,'.');
	}
	else 
	{
		// read in the puzzle from a one-line string
		puzzleString = a.GetArg(string("puzzle"),string());
		if ( puzzleString.length() == 0 )
		{
			// try from a file
			string fileName = a.GetArg(string("file"),string());
			puzzleString = ReadFile(fileName);
		}
		if ( puzzleString.length() == 0 )
		{
			cerr << "no puzzle specified" << endl;
			exit(0);
		}
	}
	Board board(puzzleString);

	int algorithm = a.GetArg("alg", 0);
	const bool mcas = (algorithm == 2);
	int timeOutSecs = a.GetArg("timeout", mcas ? 180 : 120);
	int nAnts = a.GetArg("ants", mcas ? 25 : 10);
	int nThreads = a.GetArg("threads", 4);
	float q0 = a.GetArg("q0", mcas ? 0.7f : 0.9f);
	float rho = a.GetArg("rho", mcas ? 0.7f : 0.9f);  // ACS rho (used in Alg 0 and Alg 2)
	float evap = a.GetArg("evap", mcas ? 0.0075f : 0.005f);
	float xi = a.GetArg("xi", mcas ? 0.5f : 0.1f);  // ACS local pheromone update: tau <- (1-xi)*tau + xi*tau0 (Lloyd & Amos Eq. 3)
	if (xi < 0.0f)
		xi = 0.0f;
	if (xi > 1.0f)
		xi = 1.0f;
	int saFreq = a.GetArg("safreq", mcas ? 25 : 0);  // SA frequency → saFrequency (Stodola et al. sa_freq). 0=disabled; e.g. 100=every 100 iters
	int saAcceptFlag = a.GetArg("saAccept", 0); // alg 0 and alg 2: 0 = conservative/hybrid (default), 1 = always accept SA result (CP-like)
	double saTinit = a.GetArg("saTinit", mcas ? 5.75 : 1.5);
	double saTmin = a.GetArg("saTmin", 0.01);
	double saCooling = a.GetArg("saCooling", 0.995);
	int commEarly = a.GetArg("commEarly", mcas ? 60 : 100);
	int commLate = a.GetArg("commLate", mcas ? 25 : 10);
	int commThreshold = a.GetArg("commThreshold", mcas ? 100 : 200);
	bool blank = a.GetArg("blank", false );
	bool verbose = a.GetArg("verbose", 0);
	bool showInitial = a.GetArg("showinitial", 0);
	bool streamProgress = a.GetArg("stream", 0);
	// Alg 2: inter-colony communication (--comm 1 default). --comm 0 skips barriers/exchange; threads are independent ACS colonies.
	int commFlag = algorithm == 2 ? a.GetArg("comm", 1) : 1;
	bool interColonyComm = (commFlag != 0);
	bool success;

	float solTime;
	Board solution;
	SudokuSolver *solver;
	
	if ( algorithm == 0 )
		solver = new SudokuAntSystem( nAnts, q0, rho, 1.0f/board.CellCount(), evap, xi, saFreq, saAcceptFlag != 0, saTinit, saTmin, saCooling);
	else if ( algorithm == 1 )
		solver = new BacktrackSearch();
	else if ( algorithm == 2 )
		solver = new ParallelSudokuAntSystem( nThreads, nAnts, q0, rho, 1.0f/board.CellCount(), evap, xi, saFreq, saAcceptFlag != 0, saTinit, saTmin, saCooling, commEarly, commLate, commThreshold, streamProgress, interColonyComm);
	else
		solver = new BacktrackSearch();

	
	if ( showInitial )
	{
		// print inital grid
		cout << "Initial constrained grid" << endl;
		cout << board.AsString(false,true) << endl;
	}
	
	success = solver->Solve(board, (float)timeOutSecs );
	solution = solver->GetSolution();
	solTime = solver->GetSolutionTime();

	// sanity chack the solution:
	if ( success && !board.CheckSolution(solution) )
	{
		cout << "solution not valid" << a.GetArg("file",string()) << " " << algorithm << endl;
		cout << "numfixedCells " << solution.FixedCellCount() << endl;

		string outString = solution.AsString(true );
		cout << outString << endl;

		success = false;
	}
	if ( !verbose )
	{
		cout << !success << endl << fixed << setprecision(5) << solTime << endl;
		// Output step count for Algorithm 1 (backtracking) in non-verbose mode
		if ( algorithm == 1 )
		{
			BacktrackSearch* backtrackSolver = dynamic_cast<BacktrackSearch*>(solver);
			if ( backtrackSolver )
			{
				cout << "iterations: " << backtrackSolver->GetStepCount() << endl;
			}
		}
	}
	else
	{
		if ( !success )
		{
			cout << "failed in time " << fixed << setprecision(5) << solTime << endl;
			// Show iterations for algorithms 0 and 2, step count for algorithm 1
			if ( algorithm == 0 )
			{
				SudokuAntSystem* antSolver = dynamic_cast<SudokuAntSystem*>(solver);
				if ( antSolver )
				{
					cout << "iterations: " << antSolver->GetIterationsCompleted() << endl;
				}
			}
			else if ( algorithm == 1 )
			{
				BacktrackSearch* backtrackSolver = dynamic_cast<BacktrackSearch*>(solver);
				if ( backtrackSolver )
				{
					cout << "iterations: " << backtrackSolver->GetStepCount() << endl;
				}
			}
			else if ( algorithm == 2 )
			{
				ParallelSudokuAntSystem* parallelSolver = dynamic_cast<ParallelSudokuAntSystem*>(solver);
				if ( parallelSolver )
				{
					cout << "iterations: " << parallelSolver->GetIterationsCompleted() << endl;
					cout << "commSetting: " << (parallelSolver->IsCommunicationEnabled() ? "on" : "off") << endl;
					cout << "communication: " << (parallelSolver->GetCommunicationOccurred() ? "yes" : "no") << endl;
					const vector<double>& idleTimes = parallelSolver->GetIdleTimePerThreadSeconds();
					double totalIdle = 0.0;
					for (size_t i = 0; i < idleTimes.size(); i++)
					{
						totalIdle += idleTimes[i];
						cout << "idleTime_thread_" << i << ": " << fixed << setprecision(5) << idleTimes[i] << endl;
					}
					cout << "idleTime_total: " << fixed << setprecision(5) << totalIdle << endl;
					int commSessions = parallelSolver->GetCommunicationSessions();
					cout << "commSessions: " << commSessions << endl;
					if (commSessions > 0)
					{
						// Average idle time per communication session (A): sum over threads / number of comm sessions
						cout << "idleTime_avg: " << fixed << setprecision(5) << (totalIdle / (double)commSessions) << endl;
					}
				}
			}
		}
		else
		{
			cout << "Solution:" << endl;
			string outString = solution.AsString( true );
			cout << outString << endl;
			cout << "solved in " << fixed << setprecision(5) << solTime << endl;
			// Show iterations for algorithms 0 and 2, step count for algorithm 1
			if ( algorithm == 0 )
			{
				SudokuAntSystem* antSolver = dynamic_cast<SudokuAntSystem*>(solver);
				if ( antSolver )
				{
					cout << "iterations: " << antSolver->GetIterationsCompleted() << endl;
				}
			}
			else if ( algorithm == 1 )
			{
				BacktrackSearch* backtrackSolver = dynamic_cast<BacktrackSearch*>(solver);
				if ( backtrackSolver )
				{
					cout << "iterations: " << backtrackSolver->GetStepCount() << endl;
				}
			}
			else if ( algorithm == 2 )
			{
				ParallelSudokuAntSystem* parallelSolver = dynamic_cast<ParallelSudokuAntSystem*>(solver);
				if ( parallelSolver )
				{
					cout << "iterations: " << parallelSolver->GetIterationsCompleted() << endl;
					cout << "commSetting: " << (parallelSolver->IsCommunicationEnabled() ? "on" : "off") << endl;
					cout << "communication: " << (parallelSolver->GetCommunicationOccurred() ? "yes" : "no") << endl;
					const vector<double>& idleTimes = parallelSolver->GetIdleTimePerThreadSeconds();
					double totalIdle = 0.0;
					for (size_t i = 0; i < idleTimes.size(); i++)
					{
						totalIdle += idleTimes[i];
						cout << "idleTime_thread_" << i << ": " << fixed << setprecision(5) << idleTimes[i] << endl;
					}
					cout << "idleTime_total: " << fixed << setprecision(5) << totalIdle << endl;
					int commSessions = parallelSolver->GetCommunicationSessions();
					cout << "commSessions: " << commSessions << endl;
					if (commSessions > 0)
					{
						// Average idle time per communication session (A): sum over threads / number of comm sessions
						cout << "idleTime_avg: " << fixed << setprecision(5) << (totalIdle / (double)commSessions) << endl;
					}
				}
			}
		}
	}
}
