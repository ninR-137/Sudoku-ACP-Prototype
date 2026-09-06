# MCAS — Multithreaded, Constraint Propagation, Ant Colony System, Simulated Annealing for Sudoku Solver

This repository implements **ACS** (Ant Colony System) and **MCAS** (multithreaded parallel ACS with ring/random communication and optional simulated annealing) for solving Sudoku puzzles. It includes a C++ solver, batch experiment scripts, and a web interface.

**Algorithms (`--alg`):**

| Value | Name | Description |
|-------|------|-------------|
| `0` | CA / ACS | Single-colony ant colony system |
| `1` | Backtrack | Exact backtracking baseline |
| `2` | MCAS | Parallel colonies (one per thread) with inter-colony communication |

---

## Prerequisites

| Component | Purpose |
|-----------|---------|
| **Visual Studio 2017+** (Desktop development with C++) | Build `sudoku_ants.exe` |
| **Python 3.10+** | Batch experiments and Flask backend |
| **Node.js 18+** and **npm** | React frontend (`webapp_improved`) only |

---

## A. Backend — Build, Run, and Test

The backend is the C++ solver (`src/`) plus the Python batch runner (`scripts/run_general.py`). This project does **not** train a machine-learning model; “testing” means compiling the solver and running it on puzzle instances (single runs or batch CSV experiments).

### A.1. One-time setup (Python)

From the repository root:

```powershell
cd "path\to\MCAS"
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r webapp\requirements.txt
```

### A.2. Build the solver (required before any run)

**Option 1 — Visual Studio (recommended on Windows)**

1. Open `vs2017\sudoku_ants.vcxproj` in Visual Studio.
2. Set configuration to **Release** and platform to **x64**.
3. Build the project (**Build → Build Solution**).

The executable should appear at:

```
vs2017\x64\Release\sudoku_ants.exe
```

**Option 2 — MSBuild from a Developer Command Prompt**

```cmd
cd path\to\MCAS\vs2017
msbuild sudoku_ants.vcxproj /p:Configuration=Release /p:Platform=x64
```

### A.3. Test a single puzzle (command line)

From the repository root, with the venv activated if you use it:

```powershell
.\vs2017\x64\Release\sudoku_ants.exe --file .\instances\9x9-database\2020_00999.txt --alg 2 --threads 4 --verbose
```

**Useful flags:**

| Flag | Meaning |
|------|---------|
| `--alg 0` | Single-colony ACS |
| `--alg 1` | Backtracking |
| `--alg 2` | Parallel MCAS (default for web app) |
| `--file <path>` | Puzzle file (see format below) |
| `--puzzle <string>` | Puzzle as one-line string instead of file |
| `--timeout <seconds>` | Time limit (default 180 for alg 2) |
| `--ants`, `--threads`, `--q0`, `--rho`, `--evap`, `--xi` | ACS parameters |
| `--safreq`, `--saTinit`, `--saTmin`, `--saCooling` | Simulated annealing |
| `--comm 0` or `1` | Inter-colony communication (alg 2 only) |
| `--verbose` | Detailed output (solution, iterations, idle time) |

**Non-verbose output** (for scripts): first line `0` = success, `1` = fail; second line = solve time in seconds.

**Puzzle file format** (`instances/*/*.txt`):

```
3
0
-1 5 3 ...    ← order 3 = 9×9; -1 = empty cell
```

### A.4. Batch testing / experiments (Python)

`scripts/run_general.py` runs many instances and writes a CSV summary.

```powershell
python scripts\run_general.py --alg 2 --instances-root instances\9x9-database --output results\test_run.csv --verbose
```

Examples:

```powershell
# MCAS on a 16×16 range
python scripts\run_general.py --alg 2 --instances-root instances\16x16-database --range-start 16x16_02203 --range-end 16x16_02436 --output results\16x16_sample.csv

# Single-colony ACS with SA every 50 iterations
python scripts\run_general.py --alg 0 --safreq 50 --instances-root instances\9x9-database --limit 10 --output results\acs_sa_sample.csv

# Parameter sweep
python scripts\run_general.py --alg 2 --sweep safreq=25,50 threads=2,4 --output results\sweep.csv
```

The script auto-detects `vs2017\x64\Release\sudoku_ants.exe`. Override with `--solver path\to\sudoku_ants.exe` if needed.

Experiment outputs are stored under `results/` (main runs, ablations, communication tests).

### A.5. Backend source layout

| Path | Role |
|------|------|
| `src/solvermain.cpp` | CLI entry point |
| `src/sudokuantsystem.*` | Algorithm 0 (ACS) |
| `src/parallelsudokuantsystem.*` | Algorithm 2 (MCAS) |
| `src/simulatedannealing.*` | SA local search |
| `src/backtracksearch.*` | Algorithm 1 |
| `src/board.*`, `src/sudokuant.*` | Grid and ants |
| `scripts/run_general.py` | Batch runner |

---

## B. Frontend — Deploy and Run the Web Application

The web UI uses the **Flask backend** (`webapp/app.py`), which calls `sudoku_ants.exe`. You can use either the **original Flask templates** or the **improved React frontend**.

**Requirement:** Build `sudoku_ants.exe` first (section A.2).

### B.1. Deploy / run — Original web app (Flask only)

Single process: Flask serves HTML and API on port 5000.

```powershell
cd path\to\MCAS
.\.venv\Scripts\Activate.ps1
python webapp\app.py
```

Open: **http://127.0.0.1:5000**

To listen on all interfaces (e.g. LAN access):

```powershell
python webapp\app.py --public
```

### B.2. Deploy / run — Improved web app (React + Flask)

Two terminals are required.

**Terminal 1 — Backend (same as B.1):**

```powershell
cd path\to\MCAS
.\.venv\Scripts\Activate.ps1
python webapp\app.py
```

**Terminal 2 — Frontend (Vite dev server):**

```powershell
cd path\to\MCAS\webapp_improved\frontend
npm install
npm run dev
```

Open: **http://127.0.0.1:5174**

API requests to `/api/*` are proxied to Flask at `http://127.0.0.1:5000` (see `webapp_improved/frontend/vite.config.js`).

### B.3. Production-style frontend build (optional)

Build static assets, then preview (Flask must still run on port 5000):

```powershell
cd webapp_improved\frontend
npm install
npm run build
npm run preview
```

For a production Flask deployment with gunicorn (Linux/macOS or WSL):

```bash
cd webapp
gunicorn -w 1 -b 0.0.0.0:5000 app:app
```

Serve the React `frontend/dist` folder with any static file server, or integrate `dist` into Flask if you add static hosting.

### B.4. Frontend source layout

| Path | Role |
|------|------|
| `webapp/app.py` | Flask API and original HTML UI |
| `webapp/templates/` | Jinja pages (index, game, create, upload, about) |
| `webapp_improved/frontend/` | Vite + React + Tailwind UI |
| `instances/` | Puzzle libraries used by the web app |

### B.5. Troubleshooting

| Problem | Solution |
|---------|----------|
| “Solver binary not found” | Complete section A.2; confirm `vs2017\x64\Release\sudoku_ants.exe` exists |
| React UI cannot solve puzzles | Start Flask on port 5000 before `npm run dev` |
| Port 5000 or 5174 in use | Stop the other process or change the port in `app.py` / `vite.config.js` |

---

## Repository structure (summary)

```
MCAS/
├── src/                 # C++ solver
├── vs2017/              # Visual Studio project
├── scripts/             # Batch experiment runner
├── instances/           # Puzzle datasets (9×9, 16×16, 25×25)
├── results/             # Experiment CSV outputs
├── webapp/              # Flask backend + legacy UI
└── webapp_improved/     # React frontend (uses webapp API)
```

---
