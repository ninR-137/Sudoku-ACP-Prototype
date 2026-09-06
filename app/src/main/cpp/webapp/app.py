"""
Minimal web app for the MT-ACS-RING Sudoku solver.
Serves a 9x9 grid UI and runs the C++ solver (with --alg 2 and --threads) on the server.
Solver runs in a background thread so the server stays responsive; the C++ binary itself
uses multiple threads. Multithreaded implementation is retained.
"""
from __future__ import annotations

import os
import re
import subprocess
import threading
import uuid
from pathlib import Path
from flask import Flask, request, jsonify, render_template, send_from_directory, Response
from datetime import datetime

app = Flask(__name__)
REPO_ROOT = Path(__file__).resolve().parents[1]
INSTANCES_ROOT = REPO_ROOT / "instances"
LIBRARY_FOLDER = "curated-dataset"
SIZES = [("9×9", 3, 81), ("16×16", 4, 256), ("25×25", 5, 625)]

# In-memory job store: job_id -> { "status": "pending"|"done"|"error", "result": {...} }
_job_store: dict[str, dict] = {}
_job_store_lock = threading.Lock()
_job_procs: dict[str, subprocess.Popen] = {}
_job_procs_lock = threading.Lock()

# Default solver parameters (match web UI defaults) for validation / exports
DEFAULT_SOLVER_PARAMS: dict = {
    "ants": 25,
    "evap": 0.0075,
    "saTinit": 5.75,
    "saTmin": 0.01,
    "safreq": 25,
    "saCooling": 0.995,
    "commThreshold": 100,
    "commEarly": 60,
    "commLate": 25,
}


def _internal_cell_to_int(ch: str, order: int) -> int:
    """Convert one internal puzzle char to instance-file integer (-1 = empty)."""
    if not ch or ch == ".":
        return -1
    if order == 3:
        if "1" <= ch <= "9":
            return ord(ch) - ord("0")
        return -1
    if order == 4:
        if "0" <= ch <= "9":
            return ord(ch) - ord("0") + 1
        if "a" <= ch <= "f":
            return 11 + (ord(ch) - ord("a"))
        if "A" <= ch <= "F":
            return 11 + (ord(ch) - ord("A"))
        return -1
    if order == 5:
        if "a" <= ch <= "y":
            return ord(ch) - ord("a") + 1
        if "A" <= ch <= "Y":
            return ord(ch) - ord("A") + 1
        return -1
    return -1


def _instance_file_from_puzzle(order: int, puzzle: str) -> str:
    """Build .txt content in the same format as instances/curated-dataset."""
    n = order * order
    num_cells = n * n
    s = (puzzle or "").ljust(num_cells, ".")[:num_cells]
    lines: list[str] = [str(order), "0"]
    for r in range(n):
        row_vals = []
        for c in range(n):
            ch = s[r * n + c]
            row_vals.append(str(_internal_cell_to_int(ch, order)))
        lines.append(" ".join(row_vals))
    return "\n".join(lines) + "\n"


def _read_instance_file(path: Path) -> tuple[int, str]:
    """Read instance .txt; returns (order, puzzle_string). Same format as desktop solver_runner."""
    text = path.read_text(encoding="utf-8", errors="replace")
    lines = [L.strip() for L in text.splitlines() if L.strip()]
    if len(lines) < 2:
        raise ValueError("Invalid instance file: need at least order and idum line")
    order = int(lines[0])
    if order not in (3, 4, 5):
        raise ValueError(f"Unsupported order {order}; use 3, 4, or 5")
    num_cells = order ** 4
    values = []
    for L in lines[2:]:
        for part in L.split():
            try:
                values.append(int(part))
            except ValueError:
                pass
    if len(values) < num_cells:
        raise ValueError(f"Instance has {len(values)} values, need {num_cells}")
    values = values[:num_cells]
    out = []
    for v in values:
        if v == -1:
            out.append(".")
        elif order == 3:
            out.append(chr(ord("1") + v - 1) if 1 <= v <= 9 else ".")
        elif order == 4:
            if 1 <= v <= 10:
                out.append(chr(ord("0") + v - 1))
            elif 11 <= v <= 16:
                out.append(chr(ord("a") + v - 11))
            else:
                out.append(".")
        else:
            out.append(chr(ord("a") + v - 1) if 1 <= v <= 25 else ".")
    return order, "".join(out)


def _list_library() -> dict:
    """List .txt files in instances/curated-dataset (recursive) by size."""
    folder = INSTANCES_ROOT / LIBRARY_FOLDER
    by_size: dict[str, list[dict]] = {label: [] for label, _, _ in SIZES}
    if not folder.is_dir():
        return by_size
    for path in sorted(folder.rglob("*.txt")):
        try:
            order, _ = _read_instance_file(path)
            label = SIZES[order - 3][0]
            rel = path.resolve().relative_to(INSTANCES_ROOT.resolve()).as_posix()
            by_size[label].append({"name": path.name, "path": rel})
        except Exception:
            pass
    return by_size


def find_solver() -> Path:
    """Resolve solver binary (sudoku_ants.exe on Windows, sudokusolver elsewhere)."""
    if os.name == "nt":
        candidates = [
            REPO_ROOT / "sudoku_ants.exe",
            REPO_ROOT / "vs2017" / "x64" / "Release" / "sudoku_ants.exe",
            REPO_ROOT / "vs2017" / "Release" / "sudoku_ants.exe",
        ]
    else:
        candidates = [
            REPO_ROOT / "sudokusolver",
            REPO_ROOT / "sudoku_ants",
        ]
    for p in candidates:
        if p.is_file():
            return p
    raise FileNotFoundError(
        "Solver binary not found. Build it first (e.g. make -f markdowns/Makefile or VS Release)."
    )


def parse_verbose_stdout(stdout: str, order: int = 3) -> dict:
    """
    Parse solver verbose output for success, time, iterations, communication, and solution grid.
    order: 3 = 9x9, 4 = 16x16, 5 = 25x25.
    """
    out = {
        "success": None,
        "time": None,
        "iterations": None,
        "communication": None,
        "solution": None,
        "raw_error": None,
    }
    # Solution grid size
    num_cells = order ** 4  # 81, 256, 625
    if order == 3:
        cell_pattern = re.compile(r"[1-9]")
    elif order == 4:
        cell_pattern = re.compile(r"[0-9a-fA-F]")
    else:
        cell_pattern = re.compile(r"[a-yA-Y]")

    lines = stdout.splitlines()

    # Success/time from verbose lines
    for line in lines:
        m = re.search(r"solved in ([0-9]*\.?[0-9]+)", line)
        if m:
            out["success"] = True
            out["time"] = float(m.group(1))
        m = re.search(r"failed in time ([0-9]*\.?[0-9]+)", line)
        if m:
            out["success"] = False
            out["time"] = float(m.group(1))
        m = re.search(r"iterations:\s*([0-9]+)", line, re.I)
        if m:
            out["iterations"] = int(m.group(1))
        m = re.search(r"communication:\s*(yes|no)", line, re.I)
        if m:
            out["communication"] = m.group(1).lower() == "yes"

    # Non-verbose fallback: "0" or "1" then time
    if out["success"] is None:
        for i, line in enumerate(lines):
            line = line.strip()
            if line in ("0", "1"):
                out["success"] = line == "0"
                if i + 1 < len(lines):
                    try:
                        out["time"] = float(lines[i + 1].strip())
                    except ValueError:
                        pass
                break

    def extract_grid_from_block(block: str) -> str | None:
        """Extract flat solution string from a grid block (Solution: full; BestSoFar: may be partial)."""
        if order == 3:
            cells = cell_pattern.findall(block)
            if len(cells) >= num_cells:
                return "".join(cells[:num_cells])
        else:
            all_nums = re.findall(r"\d+", block)
            max_val = order * order
            values = [int(s) for s in all_nums if 1 <= int(s) <= max_val][:num_cells]
            if len(values) == num_cells:
                if order == 4:
                    return "".join(
                        chr(ord("0") + v - 1) if v <= 10 else chr(ord("a") + v - 11)
                        for v in values
                    )
                return "".join(chr(ord("a") + v - 1) for v in values)
        return None

    def extract_partial_grid(block: str) -> str | None:
        """Extract partial grid (chars + '.') for BestSoFar - C++ AsString(false) output."""
        if order == 3:
            pat = re.compile(r"[1-9.]")
        elif order == 4:
            pat = re.compile(r"[0-9a-fA-F.]")
        else:
            pat = re.compile(r"[a-yA-Y.]")
        chars = pat.findall(block)[:num_cells]
        if not chars:
            return None
        return ("".join(chars) + "." * num_cells)[:num_cells]

    # Extract BestSoFar blocks (partial solutions during solve - uses char+dot format)
    # Board output has "---" inside grid separators; only a line exactly "---" ends the block
    for i, line in enumerate(lines):
        if "BestSoFar:" in line:
            end = i + 1
            while end < len(lines) and lines[end].strip() != "---":
                end += 1
            block = " ".join(lines[i + 1 : end])
            sol = extract_partial_grid(block)
            if sol:
                out["solution"] = sol

    # Extract final solution: between "Solution:" and "solved in"
    solution_start = None
    solution_end = None
    for i, line in enumerate(lines):
        if "Solution:" in line:
            solution_start = i + 1
        if solution_start is not None and "solved in" in line:
            solution_end = i
            break
    if solution_start is not None and solution_end is not None:
        block = " ".join(lines[solution_start:solution_end])
        sol = extract_grid_from_block(block)
        if sol:
            out["solution"] = sol

    return out


def _run_solver_sync(
    job_id: str | None,
    puzzle: str,
    timeout: int,
    threads: int,
    alg: int,
    order: int = 3,
    extra_params: dict | None = None,
) -> dict:
    """
    Run C++ solver. If job_id is set, read stdout in real time and update
    _job_store[job_id]["best_solution"] whenever a solution block is parsed.
    Returns result dict for one job.
    """
    try:
        solver_path = find_solver()
    except FileNotFoundError as e:
        return {"status": "error", "result": {"error": str(e)}}
    cmd = [
        str(solver_path),
        "--puzzle", puzzle,
        "--alg", str(alg),
        "--timeout", str(timeout),
        "--verbose",
    ]
    if alg == 2:
        cmd.extend(["--threads", str(threads), "--stream"])
    if extra_params:
        for k, v in extra_params.items():
            cmd.extend([f"--{k}", str(v)])
    try:
        proc = subprocess.Popen(
            cmd,
            cwd=str(REPO_ROOT),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )
        if job_id is not None:
            with _job_procs_lock:
                _job_procs[job_id] = proc
        buffer_lines: list[str] = []

        def reader() -> None:
            if proc.stdout:
                for line in iter(proc.stdout.readline, ""):
                    buffer_lines.append(line)
                    full = "".join(buffer_lines)
                    parsed = parse_verbose_stdout(full, order=order)
                    if parsed.get("solution") and job_id is not None:
                        with _job_store_lock:
                            job = _job_store.get(job_id)
                            if job and job.get("status") == "pending":
                                _job_store[job_id] = {**job, "best_solution": parsed["solution"]}

        reader_thread = threading.Thread(target=reader, daemon=True)
        reader_thread.start()

        try:
            proc.wait(timeout=timeout + 15)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
            raise

        reader_thread.join(timeout=2.0)
        buffer = "".join(buffer_lines)
        if proc.stderr:
            stderr_output = proc.stderr.read()
        else:
            stderr_output = ""

        parsed = parse_verbose_stdout(buffer, order=order)

        if proc.returncode != 0 and parsed["success"] is None:
            parsed["raw_error"] = stderr_output.strip() or f"Exit code {proc.returncode}"

        return {
            "status": "done",
            "result": {
                "success": parsed["success"],
                "solution": parsed["solution"],
                "time": parsed["time"],
                "iterations": parsed["iterations"],
                "communication": parsed["communication"],
                "error": parsed.get("raw_error"),
            },
            "best_solution": parsed["solution"],
        }
    except subprocess.TimeoutExpired:
        return {"status": "error", "result": {"error": "Solver timeout"}}
    except Exception as e:
        return {"status": "error", "result": {"error": str(e)}}
    finally:
        if job_id is not None:
            with _job_procs_lock:
                _job_procs.pop(job_id, None)


def _worker(job_id: str, puzzle: str, timeout: int, threads: int, alg: int, order: int, extra_params: dict) -> None:
    """Background thread: run solver with streaming stdout and store result."""
    try:
        outcome = _run_solver_sync(job_id, puzzle, timeout, threads, alg, order, extra_params)
    except Exception as e:
        outcome = {"status": "error", "result": {"error": str(e)}}
    with _job_store_lock:
        existing = _job_store.get(job_id, {})
        _job_store[job_id] = {
            "status": outcome["status"],
            "result": outcome.get("result"),
            "best_solution": outcome.get("best_solution") or existing.get("best_solution"),
        }


@app.route("/")
def menu():
    return render_template("menu.html")


@app.route("/play")
def play_page():
    return render_template("index.html")

@app.route("/game")
def game_page():
    return render_template("game.html")


@app.route("/create")
def create_page():
    return render_template("create.html")


@app.route("/about")
def about_page():
    return render_template("about.html")


@app.route("/upload")
def upload_page():
    return render_template("upload.html")


@app.route("/logo")
def logo():
    """Serve SudoPhase_Logo.png from webapp (templates or root)."""
    directory = Path(app.root_path)
    for name in ("SudoPhase_Logo.png", "templates/SudoPhase_Logo.png"):
        path = directory / name
        if path.is_file():
            return send_from_directory(directory, name)
    return send_from_directory(directory, "static/logo.png")


@app.route("/api/library", methods=["GET"])
def library():
    """GET /api/library -> grouped instances from instances/curated-dataset."""
    return jsonify(_list_library())


@app.route("/api/instance/<path:filename>", methods=["GET"])
def get_instance(filename: str):
    """GET /api/instance/<path> from instances/curated-dataset -> { order, puzzle }."""
    parts = filename.replace("\\", "/").strip("/").split("/")
    if any(p in ("", ".", "..") for p in parts):
        return jsonify({"error": "Invalid filename"}), 400
    library_root = (INSTANCES_ROOT / LIBRARY_FOLDER).resolve()
    # Allow full relative path (preferred)
    candidate = (INSTANCES_ROOT / Path(*parts)).resolve()
    path = candidate if str(candidate).startswith(str(library_root)) else None
    # Backward-compatible fallback: single filename lookup under library root (recursive)
    if path is None and len(parts) == 1:
        matches = sorted(library_root.rglob(parts[0]))
        path = matches[0].resolve() if matches else None
    if path is None or (not path.is_file()) or (not str(path).startswith(str(library_root))):
        return jsonify({"error": "Instance not found"}), 404
    try:
        order, puzzle = _read_instance_file(path)
        return jsonify({"order": order, "puzzle": puzzle})
    except Exception as e:
        return jsonify({"error": str(e)}), 400


@app.route("/api/solve", methods=["POST"])
def solve():
    """
    POST JSON: { "puzzle": "...", "timeout": 180, "threads": 4, "alg": 2 }
    Starts the C++ solver in a background thread (multithreaded: threads inside C++).
    Returns: { "job_id": "..." }. Poll GET /api/status/<job_id> for result.
    """
    try:
        data = request.get_json() or {}
        puzzle = (data.get("puzzle") or "").strip()
        if not puzzle:
            return jsonify({"error": "Missing or empty 'puzzle'"}), 400
        order = int(data.get("order", 3))
        expected_len = order ** 4
        if len(puzzle) != expected_len:
            return jsonify({"error": f"Puzzle must be {expected_len} characters for order {order}"}), 400
        timeout = int(data.get("timeout", 180))
        threads = int(data.get("threads", 4))
        alg = int(data.get("alg", 2))

        extra_params = {}
        for param_name, param_type, default_val in [
            ("ants", int, 25),
            ("evap", float, 0.0075),
            ("saTinit", float, 5.75),
            ("saTmin", float, 0.01),
            ("safreq", int, 25),
            ("saCooling", float, 0.995),
            ("commThreshold", int, 100),
            ("commEarly", int, 60),
            ("commLate", int, 25),
        ]:
            if param_name in data:
                extra_params[param_name] = param_type(data[param_name])

        job_id = str(uuid.uuid4())
        with _job_store_lock:
            _job_store[job_id] = {"status": "pending", "result": None, "best_solution": None}

        t = threading.Thread(
            target=_worker,
            args=(job_id, puzzle, timeout, threads, alg, order, extra_params),
            daemon=True,
        )
        t.start()

        return jsonify({"job_id": job_id}), 202
    except FileNotFoundError as e:
        return jsonify({"error": str(e)}), 500
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/api/status/<job_id>", methods=["GET"])
def status(job_id: str):
    """
    GET /api/status/<job_id>
    Returns: { "status": "pending"|"done"|"error", "result": {...} when not pending }
    """
    with _job_store_lock:
        job = _job_store.get(job_id)
    if not job:
        return jsonify({"error": "Unknown job_id"}), 404
    return jsonify(job)


@app.route("/api/cancel/<job_id>", methods=["POST"])
def cancel(job_id: str):
    """Cancel a running solver job."""
    with _job_store_lock:
        job = _job_store.get(job_id)
        if not job:
            return jsonify({"error": "Unknown job_id"}), 404
        if job.get("status") != "pending":
            return jsonify({"status": job.get("status"), "result": job.get("result")}), 200

        _job_store[job_id] = {
            **job,
            "status": "error",
            "result": {"error": "Cancelled by user"},
        }

    with _job_procs_lock:
        proc = _job_procs.get(job_id)
    if proc is not None:
        try:
            proc.terminate()
            proc.wait(timeout=2)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass

    return jsonify({"status": "error", "result": {"error": "Cancelled by user"}}), 200


def _pdf_escape(s: str) -> str:
    return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def _format_grid(puzzle: str, order: int) -> list[str]:
    n = order * order
    s = (puzzle or "").ljust(n * n, ".")[: n * n]
    lines: list[str] = []
    for r in range(n):
        row = s[r * n : (r + 1) * n]
        # TXT export follows instance-style numeric encoding:
        # -1 for blank cells, numeric values for filled cells.
        row_disp = " ".join(_display_cell(ch, order) if ch != "." else "-1" for ch in row)
        lines.append(row_disp)
    return lines


def _display_cell(ch: str, order: int) -> str:
    if not ch or ch == ".":
        return "."
    if order == 3:
        return ch if "1" <= ch <= "9" else "."
    if order == 4:
        if "0" <= ch <= "9":
            return str(ord(ch) - ord("0") + 1)
        lo = ch.lower()
        if "a" <= lo <= "f":
            return str(11 + ord(lo) - ord("a"))
        return "."
    if order == 5:
        lo = ch.lower()
        if "a" <= lo <= "y":
            return str(ord(lo) - ord("a") + 1)
        return "."
    return "."


def _grid_matrix(puzzle: str, order: int) -> list[list[str]]:
    n = order * order
    s = (puzzle or "").ljust(n * n, ".")[: n * n]
    out: list[list[str]] = []
    for r in range(n):
        row: list[str] = []
        for c in range(n):
            row.append(_display_cell(s[r * n + c], order))
        out.append(row)
    return out


def _make_simple_pdf(lines: list[str]) -> bytes:
    """
    Minimal single-page PDF generator (no external deps).
    Renders each line with Helvetica at fixed positions.
    """
    # Build content stream
    y_start = 800
    leading = 14
    content_parts = ["BT", "/F1 10 Tf", f"72 {y_start} Td"]
    first = True
    for line in lines:
        if not first:
            content_parts.append(f"0 -{leading} Td")
        first = False
        content_parts.append(f"({_pdf_escape(line)}) Tj")
    content_parts.append("ET")
    content = "\n".join(content_parts).encode("utf-8")

    objects: list[bytes] = []
    objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    objects.append(b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    objects.append(
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>"
    )
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    objects.append(b"<< /Length %d >>\nstream\n%s\nendstream" % (len(content), content))

    # Assemble PDF with xref
    out = bytearray()
    out.extend(b"%PDF-1.4\n")
    offsets = [0]
    for i, obj in enumerate(objects, start=1):
        offsets.append(len(out))
        out.extend(f"{i} 0 obj\n".encode("ascii"))
        out.extend(obj)
        out.extend(b"\nendobj\n")
    xref_pos = len(out)
    out.extend(f"xref\n0 {len(objects)+1}\n".encode("ascii"))
    out.extend(b"0000000000 65535 f \n")
    for off in offsets[1:]:
        out.extend(f"{off:010d} 00000 n \n".encode("ascii"))
    out.extend(
        f"trailer\n<< /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{xref_pos}\n%%EOF\n".encode(
            "ascii"
        )
    )
    return bytes(out)


def _make_report_pdf(
    order: int,
    initial_puzzle: str,
    solved_puzzle: str,
    current_puzzle: str,
    report_kind: str,
    include_params: bool,
    params: dict | None,
) -> bytes:
    """
    Draw a structured report similar to the desktop-style export:
    - solved: Original + Solved (side-by-side), optional params
    - initial: Original only
    - progress: Original + Current progress (side-by-side)
    """
    params = params or {}
    n = order * order
    is_solved = report_kind == "solved"
    is_progress = report_kind == "progress"
    left_title = "Original Puzzle"
    right_title = "Solved Puzzle" if is_solved else "Current Progress"
    title = (
        "SudoPHASE - Initial Puzzle Report"
        if report_kind == "initial"
        else "SudoPHASE - Progress/Solved Puzzle Report"
    )

    left_grid = _grid_matrix(initial_puzzle, order)
    right_grid = _grid_matrix(solved_puzzle if is_solved else current_puzzle, order) if (is_solved or is_progress) else []

    # Page geometry (A4-like)
    page_w, page_h = 842, 595
    margin = 28
    top = page_h - margin
    available_w = page_w - 2 * margin
    gap = 32 if (is_solved or is_progress) else 0
    panel_w = (available_w - gap) / (2 if (is_solved or is_progress) else 1)
    # Fit grid width to panel
    cell = min(14.0, max(7.0, (panel_w - 16) / n))
    grid_w = cell * n
    grid_h = cell * n
    left_x = margin
    right_x = margin + panel_w + gap
    grid_top_y = top - 120
    grid_bottom_y = grid_top_y - grid_h

    def esc(s: str) -> str:
        return _pdf_escape(str(s))

    content: list[str] = []
    # Header
    content.append("BT")
    content.append("/F1 24 Tf")
    content.append(f"{margin} {top} Td")
    content.append(f"({esc(title)}) Tj")
    content.append("ET")

    generated = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    content.extend(
        [
            "BT",
            "/F1 13 Tf",
            f"{margin} {top - 26} Td",
            f"(Generated: {esc(generated)}) Tj",
            "ET",
            "BT",
            "/F1 13 Tf",
            f"{margin} {top - 44} Td",
            f"(Size: {n}x{n}) Tj",
            "ET",
        ]
    )
    # Header intentionally excludes algorithm line per UI requirement.

    def draw_grid(grid: list[list[str]], x: float, y_top: float, label: str) -> None:
        content.extend(
            [
                "BT",
                "/F1 18 Tf",
                f"{x} {y_top + 18} Td",
                f"({esc(label)}) Tj",
                "ET",
            ]
        )
        # Grid lines
        for i in range(n + 1):
            xx = x + i * cell
            yy = y_top - i * cell
            lw_v = 1.2 if (i % order == 0) else 0.4
            lw_h = 1.2 if (i % order == 0) else 0.4
            content.append(f"{lw_v:.2f} w")
            content.append(f"{xx:.2f} {y_top:.2f} m {xx:.2f} {grid_bottom_y:.2f} l S")
            content.append(f"{lw_h:.2f} w")
            content.append(f"{x:.2f} {yy:.2f} m {x + grid_w:.2f} {yy:.2f} l S")
        # Cell values
        fs = max(6.0, min(10.0, cell * 0.5))
        for r in range(n):
            for c in range(n):
                val = grid[r][c]
                if val == ".":
                    continue
                tx = x + c * cell + 2.0
                ty = y_top - (r + 1) * cell + 2.0
                content.extend(
                    [
                        "BT",
                        f"/F1 {fs:.2f} Tf",
                        f"{tx:.2f} {ty:.2f} Td",
                        f"({esc(val)}) Tj",
                        "ET",
                    ]
                )

    draw_grid(left_grid, left_x, grid_top_y, left_title)
    if is_solved or is_progress:
        draw_grid(right_grid, right_x, grid_top_y, right_title)

    page_streams: list[bytes] = ["\n".join(content).encode("utf-8")]
    if include_params and params:
        # Put parameters on a dedicated page so large grids (e.g. 25x25) never clip the list.
        p2: list[str] = []
        p2.extend(
            [
                "BT",
                "/F1 22 Tf",
                f"{margin} {top} Td",
                f"({esc(title)}) Tj",
                "ET",
                "BT",
                "/F1 13 Tf",
                f"{margin} {top - 26} Td",
                f"(Generated: {esc(generated)}) Tj",
                "ET",
                "BT",
                "/F1 13 Tf",
                f"{margin} {top - 44} Td",
                f"(Size: {n}x{n}) Tj",
                "ET",
                "BT",
                "/F1 16 Tf",
                f"{margin} {top - 78} Td",
                "(Parameter Values Used) Tj",
                "ET",
            ]
        )
        y = top - 102
        for k in sorted(params.keys()):
            p2.extend(
                [
                    "BT",
                    "/F1 12 Tf",
                    f"{margin} {y:.2f} Td",
                    f"({esc(str(k))}: {esc(str(params[k]))}) Tj",
                    "ET",
                ]
            )
            y -= 16
        page_streams.append("\n".join(p2).encode("utf-8"))

    num_pages = len(page_streams)
    page_ids = [3 + i for i in range(num_pages)]
    content_ids = [3 + num_pages + i for i in range(num_pages)]
    font_id = 3 + (2 * num_pages)

    kids = " ".join(f"{pid} 0 R" for pid in page_ids)
    objects: list[bytes] = []
    objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    objects.append(f"<< /Type /Pages /Kids [{kids}] /Count {num_pages} >>".encode("ascii"))
    for i in range(num_pages):
        objects.append(
            (
                f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {page_w} {page_h}] "
                f"/Resources << /Font << /F1 {font_id} 0 R >> >> "
                f"/Contents {content_ids[i]} 0 R >>"
            ).encode("ascii")
        )
    for stream in page_streams:
        objects.append(b"<< /Length %d >>\nstream\n%s\nendstream" % (len(stream), stream))
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

    out = bytearray()
    out.extend(b"%PDF-1.4\n")
    offsets = [0]
    for i, obj in enumerate(objects, start=1):
        offsets.append(len(out))
        out.extend(f"{i} 0 obj\n".encode("ascii"))
        out.extend(obj)
        out.extend(b"\nendobj\n")
    xref_pos = len(out)
    out.extend(f"xref\n0 {len(objects)+1}\n".encode("ascii"))
    out.extend(b"0000000000 65535 f \n")
    for off in offsets[1:]:
        out.extend(f"{off:010d} 00000 n \n".encode("ascii"))
    out.extend(
        f"trailer\n<< /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{xref_pos}\n%%EOF\n".encode("ascii")
    )
    return bytes(out)


def _make_report_txt(
    order: int,
    initial_puzzle: str,
    solved_puzzle: str,
    current_puzzle: str,
    report_kind: str,
    include_params: bool,
    params: dict | None,
) -> str:
    n = order * order
    title = (
        "SudoPHASE - Initial Puzzle Report"
        if report_kind == "initial"
        else "SudoPHASE - Progress/Solved Puzzle Report"
    )
    lines: list[str] = [title]
    lines.append(f"Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"Size: {n}x{n}")
    lines.append("")
    lines.append("Original Puzzle")
    lines.extend(_format_grid(initial_puzzle, order))
    if report_kind == "progress":
        lines.append("")
        lines.append("Current Progress")
        lines.extend(_format_grid(current_puzzle, order))
    elif report_kind == "solved":
        lines.append("")
        lines.append("Solved Puzzle")
        lines.extend(_format_grid(solved_puzzle, order))
    if include_params and params:
        lines.append("")
        lines.append("Parameter Values Used")
        for k in sorted(params.keys()):
            lines.append(f"{k}: {params[k]}")
    lines.append("")
    return "\n".join(lines)


@app.route("/api/pdf", methods=["POST"])
def pdf():
    """
    POST JSON: { order, puzzle, solution, params, currentPuzzle? }
    If currentPuzzle is set, includes a "Current progress" section (for save-progress export).
    """
    data = request.get_json() or {}
    order = int(data.get("order", 3))
    puzzle = str(data.get("puzzle") or "")
    solution = str(data.get("solution") or "")
    current_puzzle = str(data.get("currentPuzzle") or "")
    params = data.get("params") or {}
    report_kind = str(data.get("reportKind") or ("solved" if solution.strip() else ("progress" if current_puzzle.strip() else "initial"))).lower()
    include_params = bool(data.get("includeParams", bool(solution.strip())))

    pdf_bytes = _make_report_pdf(
        order=order,
        initial_puzzle=puzzle,
        solved_puzzle=solution,
        current_puzzle=current_puzzle,
        report_kind=report_kind,
        include_params=include_params,
        params=params if isinstance(params, dict) else {},
    )
    filename = "sudophase_export.pdf"
    return Response(
        pdf_bytes,
        mimetype="application/pdf",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@app.route("/api/export/report-txt", methods=["POST"])
def export_report_txt():
    """POST JSON report export in textual report style (not curated-dataset format)."""
    data = request.get_json() or {}
    order = int(data.get("order", 3))
    puzzle = str(data.get("puzzle") or "")
    solution = str(data.get("solution") or "")
    current_puzzle = str(data.get("currentPuzzle") or "")
    report_kind = str(data.get("reportKind") or ("solved" if solution.strip() else ("progress" if current_puzzle.strip() else "initial"))).lower()
    include_params = bool(data.get("includeParams", bool(solution.strip())))
    params = data.get("params") if isinstance(data.get("params"), dict) else {}
    body = _make_report_txt(
        order=order,
        initial_puzzle=puzzle,
        solved_puzzle=solution,
        current_puzzle=current_puzzle,
        report_kind=report_kind,
        include_params=include_params,
        params=params,
    )
    return Response(
        body,
        mimetype="text/plain; charset=utf-8",
        headers={"Content-Disposition": 'attachment; filename="report.txt"'},
    )


@app.route("/api/export/instance-txt", methods=["POST"])
def export_instance_txt():
    """POST JSON: { order, puzzle } — curated-dataset style .txt for the clue puzzle."""
    data = request.get_json() or {}
    order = int(data.get("order", 3))
    puzzle = (data.get("puzzle") or "").strip()
    expected_len = order**4
    if len(puzzle) != expected_len:
        return jsonify({"error": f"Puzzle must be {expected_len} characters"}), 400
    body = _instance_file_from_puzzle(order, puzzle)
    return Response(
        body,
        mimetype="text/plain; charset=utf-8",
        headers={"Content-Disposition": 'attachment; filename="puzzle.txt"'},
    )


@app.route("/api/export/progress-txt", methods=["POST"])
def export_progress_txt():
    """POST JSON: { order, initialPuzzle, currentPuzzle } — two instance blocks in one file."""
    data = request.get_json() or {}
    order = int(data.get("order", 3))
    initial = (data.get("initialPuzzle") or "").strip()
    current = (data.get("currentPuzzle") or "").strip()
    expected_len = order**4
    if len(initial) != expected_len or len(current) != expected_len:
        return jsonify({"error": f"Puzzles must be {expected_len} characters"}), 400
    parts = [
        "# SudoPHASE — initial puzzle (curated format)",
        _instance_file_from_puzzle(order, initial).rstrip("\n"),
        "",
        "# Current progress (curated format)",
        _instance_file_from_puzzle(order, current).rstrip("\n"),
        "",
    ]
    body = "\n".join(parts)
    return Response(
        body,
        mimetype="text/plain; charset=utf-8",
        headers={"Content-Disposition": 'attachment; filename="progress.txt"'},
    )


@app.route("/api/validate-create", methods=["POST"])
def validate_create():
    """
    Check that a user-built puzzle is solvable by the solver within timeout
    using default parameters. Used before Create Puzzle can be finalized.
    """
    data = request.get_json() or {}
    puzzle = (data.get("puzzle") or "").strip()
    order = int(data.get("order", 3))
    expected_len = order**4
    if not puzzle or len(puzzle) != expected_len:
        return jsonify({"valid": False, "error": f"Puzzle must be {expected_len} characters"}), 400
        timeout = int(data.get("timeout", 180))
    try:
        outcome = _run_solver_sync(
            None,
            puzzle,
            timeout,
            4,
            2,
            order,
            dict(DEFAULT_SOLVER_PARAMS),
        )
    except Exception as e:
        return jsonify({"valid": False, "error": str(e)}), 200
    if outcome.get("status") != "done":
        err = (outcome.get("result") or {}).get("error") or "Solver error"
        return jsonify({"valid": False, "error": err}), 200
    res = outcome.get("result") or {}
    if res.get("success") and res.get("solution"):
        return jsonify(
            {
                "valid": True,
                "time": res.get("time"),
                "iterations": res.get("iterations"),
            }
        ), 200
    return jsonify(
        {
            "valid": False,
            "error": "Puzzle could not be solved within the time limit (or has no solution).",
            "time": res.get("time"),
        }
    ), 200


if __name__ == "__main__":
    import sys
    host = "0.0.0.0" if "--public" in sys.argv else "127.0.0.1"
    app.run(host=host, port=5000, debug=False)
