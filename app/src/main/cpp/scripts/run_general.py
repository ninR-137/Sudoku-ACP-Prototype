#!/usr/bin/env python3
"""
Utility to batch-run all general Sudoku instances through the ACS solver.

Examples:
    python scripts/run_general.py --verbose
    python scripts/run_general.py --instances-root instances/9x9-database --range-start 2020_00004 --range-end 2020_00483 --output results/9x9_00004_00483.csv
    python scripts/run_general.py --instances-root instances/16x16-database --range-start 16x16_02203 --range-end 16x16_02436 --output results/16x16_02203_02436.csv
"""
from __future__ import annotations

import argparse
import csv
import itertools
import os
import re
import sys
import statistics
from dataclasses import dataclass
from pathlib import Path
from subprocess import CompletedProcess, run, PIPE
from typing import Dict, Iterable, List, Optional, Sequence, Tuple


@dataclass(frozen=True)
class InstanceMetadata:
    path: Path
    size_label: str
    fixed_percentage: Optional[int]
    instance_id: Optional[int]

    @property
    def relative_path(self) -> str:
        repo_root = find_repo_root()
        return str(self.path.relative_to(repo_root))


def find_repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def default_solver_candidates() -> Sequence[str]:
    if os.name == "nt":
        return (
            "sudoku_ants.exe",
            "sudoku_ants",
            os.path.join("vs2017", "x64", "Release", "sudoku_ants.exe"),
            os.path.join("vs2017", "Release", "sudoku_ants.exe"),
        )
    return (
        "./sudoku_ants",
        "sudoku_ants",
        os.path.join("vs2017", "x64", "Release", "sudoku_ants"),
        os.path.join("vs2017", "Release", "sudoku_ants"),
    )


def resolve_solver_path(user_value: Optional[str]) -> Path:
    repo_root = find_repo_root()
    if user_value:
        solver_path = Path(user_value)
        if not solver_path.is_absolute():
            solver_path = (repo_root / solver_path).resolve()
        if solver_path.exists():
            return solver_path
        raise FileNotFoundError(f"Solver binary not found at '{solver_path}'.")

    for candidate in default_solver_candidates():
        candidate_path = (repo_root / candidate).resolve()
        if candidate_path.exists() and candidate_path.is_file():
            return candidate_path

    raise FileNotFoundError(
        "Solver binary not found. Build it first (e.g. run `make -f Makefile`)."
    )


def iter_instance_files(instances_root: Path) -> Iterable[Path]:
    if not instances_root.exists():
        raise FileNotFoundError(f"Instances folder not found: '{instances_root}'.")
    return sorted(instances_root.glob("*.txt"))


def iter_all_instance_files(repo_root: Path) -> Iterable[Path]:
    """Iterate over general, logic-solvable, and database instances."""
    all_files = []
    
    # General instances
    general_root = repo_root / "instances" / "general"
    if general_root.exists():
        all_files.extend(general_root.glob("*.txt"))
    
    # Logic-solvable instances
    logic_root = repo_root / "instances" / "logic-solvable"
    if logic_root.exists():
        all_files.extend(logic_root.glob("*.txt"))
    
    # 16x16 database instances
    database16x16_root = repo_root / "instances" / "16x16-database"
    if database16x16_root.exists():
        all_files.extend(database16x16_root.glob("*.txt"))

    # 9x9 database instances
    database9x9_root = repo_root / "instances" / "9x9-database"
    if database9x9_root.exists():
        all_files.extend(database9x9_root.glob("*.txt"))

    # 25x25 database instances
    database25x25_root = repo_root / "instances" / "25x25-database"
    if database25x25_root.exists():
        all_files.extend(database25x25_root.glob("*.txt"))
    
    if not all_files:
        raise FileNotFoundError(
            "No instance files found in 'instances/general', "
            "'instances/logic-solvable', or any database folders "
            "('instances/16x16-database', 'instances/9x9-database', "
            "'instances/25x25-database')."
        )
    
    return sorted(all_files)


def parse_metadata(path: Path) -> InstanceMetadata:
    name = path.stem
    match = re.match(r"inst(?P<size>[0-9x]+)_(?P<fixed>\d+)_(?P<idx>\d+)", name)
    size = None
    fixed = None
    idx = None
    if match:
        size = match.group("size")
        fixed = int(match.group("fixed"))
        idx = int(match.group("idx"))
    else:
        # Check for 16x16-database format: 16x16_00001, 16x16_00002, etc.
        database16x16_match = re.match(r"16x16_(?P<idx>\d+)", name)
        if database16x16_match:
            size = "16x16"  # 16x16 database contains 16x16 sudokus
            idx = int(database16x16_match.group("idx"))
        else:
            # Logic-solvable instances: use puzzle name as size_label
            size = name
    return InstanceMetadata(path=path, size_label=size or "unknown", fixed_percentage=fixed, instance_id=idx)


def sort_instance_metadata(instances: Sequence[InstanceMetadata]) -> List[InstanceMetadata]:
    size_order = {"9x9": 0, "16x16": 1, "25x25": 2}

    def key(meta: InstanceMetadata) -> Tuple[int, int, int, str]:
        return (
            size_order.get(meta.size_label, 99),
            meta.fixed_percentage if meta.fixed_percentage is not None else 999,
            meta.instance_id if meta.instance_id is not None else 999,
            meta.path.name,
        )

    return sorted(instances, key=key)


def format_instance_argument(instance_path: Path, repo_root: Path) -> str:
    try:
        rel_path = instance_path.relative_to(repo_root)
    except ValueError:
        return str(instance_path)
    prefixed = Path(".") / rel_path
    return str(prefixed)


def build_solver_command(
    solver_path: Path,
    instance_path: Path,
    repo_root: Path,
    args: argparse.Namespace,
) -> List[str]:
    file_arg = format_instance_argument(instance_path, repo_root)
    cmd: List[str] = [str(solver_path), "--file", file_arg, "--alg", str(args.alg), "--timeout", str(args.timeout)]

    if args.ants is not None:
        cmd.extend(("--ants", str(args.ants)))
    if args.threads is not None:
        cmd.extend(("--threads", str(args.threads)))
    if args.q0 is not None:
        cmd.extend(("--q0", str(args.q0)))
    if args.rho is not None:
        cmd.extend(("--rho", str(args.rho)))
    if args.evap is not None:
        cmd.extend(("--evap", str(args.evap)))
    if args.alg == 0 or args.alg == 2:
        cmd.extend(("--xi", str(args.xi)))
    if args.safreq > 0:
        cmd.extend(("--safreq", str(args.safreq)))
    if getattr(args, "sa_accept", 0) == 1:
        cmd.extend(("--saAccept", "1"))
    cmd.extend(("--saTinit", str(args.sa_tinit)))
    cmd.extend(("--saTmin", str(args.sa_tmin)))
    cmd.extend(("--saCooling", str(args.sa_cooling)))
    if args.alg == 2:
        cmd.extend(("--commEarly", str(args.comm_early)))
        cmd.extend(("--commLate", str(args.comm_late)))
        cmd.extend(("--commThreshold", str(args.comm_threshold)))
        cmd.extend(("--comm", str(args.comm)))
    # Always add verbose for algorithms 0 and 2 to get iteration count
    if args.alg == 0 or args.alg == 2 or args.solver_verbose:
        cmd.append("--verbose")
    return cmd


def run_solver(cmd: Sequence[str], cwd: Path, timeout: Optional[float], show_progress: bool = False) -> CompletedProcess:
    if show_progress:
        # Don't capture stderr so progress messages show in real-time
        return run(
            list(cmd),
            cwd=str(cwd),
            stdout=PIPE,
            stderr=None,  # Let stderr go directly to console
            text=True,
            timeout=timeout,
        )
    else:
        return run(
            list(cmd),
            cwd=str(cwd),
            capture_output=True,
            text=True,
            timeout=timeout,
        )


def parse_solver_output(stdout: str, stderr: str) -> Tuple[Optional[bool], Optional[float], Optional[int], Optional[bool], Optional[float], Optional[int], str, str]:
    stdout_lines = [line.strip() for line in stdout.splitlines() if line.strip()]
    stderr_lines = [line.strip() for line in stderr.splitlines() if line.strip()]

    success: Optional[bool] = None
    solve_time: Optional[float] = None
    iterations: Optional[int] = None
    communication: Optional[bool] = None
    idle_time_avg: Optional[float] = None
    comm_sessions: Optional[int] = None

    # Combine stdout and stderr for parsing (iterations might be in either)
    all_lines = stdout_lines + stderr_lines

    # First pass: look for non-verbose format (success indicator "0" or "1" followed by time)
    for i, line in enumerate(stdout_lines):
        if line in {"0", "1"} and success is None:
            success = (line == "0")
            # In non-verbose mode, the time is on the next line
            if i + 1 < len(stdout_lines):
                try:
                    solve_time = float(stdout_lines[i + 1])
                    break  # Found both success and time, can exit early
                except (ValueError, IndexError):
                    pass
            continue

    # Second pass: look for verbose format patterns
    for line in all_lines:
        solved_match = re.search(r"solved in ([0-9]*\.?[0-9]+)", line)
        if solved_match:
            solve_time = float(solved_match.group(1))
            success = True
            continue

        failed_match = re.search(r"failed in time ([0-9]*\.?[0-9]+)", line)
        if failed_match:
            solve_time = float(failed_match.group(1))
            success = False
            continue

        # Parse iterations (for algorithms 0, 1, and 2)
        # Algorithm 0 and 2: actual iterations; Algorithm 1: step count
        iter_match = re.search(r"iterations:\s*([0-9]+)", line, re.IGNORECASE)
        if iter_match:
            iterations = int(iter_match.group(1))
            continue

        # Parse communication flag for algorithm 2
        comm_match = re.search(r"communication:\s*(yes|no)", line, re.IGNORECASE)
        if comm_match:
            communication = (comm_match.group(1).lower() == "yes")
            continue

        # Parse average barrier idle time for algorithm 2 (printed by solver in verbose mode)
        idle_match = re.search(r"idleTime_avg:\s*([0-9]*\.?[0-9]+)", line, re.IGNORECASE)
        if idle_match:
            idle_time_avg = float(idle_match.group(1))
            continue

        # Parse number of completed communication sessions (barrier exchanges)
        comms_match = re.search(r"commSessions:\s*([0-9]+)", line, re.IGNORECASE)
        if comms_match:
            comm_sessions = int(comms_match.group(1))
            continue

    # Fallback: check stdout for time if not found yet (skip "0" and "1" as they're success indicators)
    if solve_time is None:
        for line in stdout_lines:
            # Skip lines that are success indicators
            if line in {"0", "1"}:
                continue
            try:
                solve_time = float(line)
                break  # Found a valid time, stop looking
            except ValueError:
                pass

    if success is None:
        # Check stderr for clues
        for line in stderr_lines:
            if "could not open file" in line.lower():
                success = False
                break

    if solve_time is not None:
        solve_time = round(solve_time, 5)

    if idle_time_avg is not None:
        idle_time_avg = round(idle_time_avg, 5)

    return success, solve_time, iterations, communication, idle_time_avg, comm_sessions, "\n".join(stdout_lines), "\n".join(stderr_lines)


def write_csv(output_path: Path, rows: Sequence[dict]) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = ["alg", "puzzle_size", "f%", "instance_id", "instance_path", "ants", "threads", "q0", "rho", "bve", "xi", "safreq", "saAccept", "saTinit", "saTmin", "saCooling", "commEarly", "commLate", "commThreshold", "comm", "timeout", "success_rate", "time_mean", "time_std", "iter_mean", "idle_mean", "idle_std", "commSessions_mean", "commSessions_std", "with_comm", "without_comm"]
    with output_path.open("w", newline="", encoding="utf-8") as csvfile:
        writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def compute_summary(total: int, successes: int, times: Sequence[float]) -> Tuple[int, int, float]:
    avg_time = sum(times) / len(times) if times else 0.0
    return total, successes, round(avg_time, 5)


def parse_multi_value_string(raw_value: str) -> List[str]:
    return [token.strip() for token in raw_value.split(",") if token.strip()]


def parse_sweep_args(raw_sweeps: Optional[Sequence[str]], parser: argparse.ArgumentParser) -> List[Dict[str, object]]:
    if not raw_sweeps:
        return [{}]

    valid_param_parsers = {
        "alg": int,
        "timeout": float,
        "ants": int,
        "threads": int,
        "q0": float,
        "rho": float,
        "evap": float,
        "xi": float,
        "safreq": int,
        "sa_accept": int,
        "sa_tinit": float,
        "sa_tmin": float,
        "sa_cooling": float,
        "comm_early": int,
        "comm_late": int,
        "comm_threshold": int,
        "comm": int,
        "logic_runs": int,
        "database16x16_runs": int,
        "database9x9_runs": int,
        "database25x25_runs": int,
        "solver_timeout": float,
    }
    alias_to_dest = {
        "saaccept": "sa_accept",
        "satinit": "sa_tinit",
        "satmin": "sa_tmin",
        "sacooling": "sa_cooling",
        "commearly": "comm_early",
        "commlate": "comm_late",
        "commthreshold": "comm_threshold",
        "database16x16-runs": "database16x16_runs",
        "database9x9-runs": "database9x9_runs",
        "database25x25-runs": "database25x25_runs",
        "solver-timeout": "solver_timeout",
    }

    per_param_values: Dict[str, List[object]] = {}
    for entry in raw_sweeps:
        if "=" not in entry:
            parser.error(f"Invalid --sweep entry '{entry}'. Expected format: key=v1,v2,...")
        key, raw_values = entry.split("=", 1)
        key_norm = key.strip().replace("-", "_").lower()
        key_norm = alias_to_dest.get(key_norm, key_norm)
        if key_norm not in valid_param_parsers:
            parser.error(
                f"Unsupported --sweep parameter '{key}'. "
                f"Supported: {', '.join(sorted(valid_param_parsers.keys()))}"
            )

        value_parser = valid_param_parsers[key_norm]
        parsed_values: List[object] = []
        for token in parse_multi_value_string(raw_values):
            try:
                parsed_values.append(value_parser(token))
            except ValueError as exc:
                parser.error(f"Invalid value '{token}' for --sweep {key}: {exc}")
        if not parsed_values:
            parser.error(f"--sweep {key} has no valid values.")

        if key_norm == "sa_accept":
            invalid_sa_accept = [v for v in parsed_values if v not in (0, 1)]
            if invalid_sa_accept:
                parser.error(f"--sweep sa_accept only accepts 0 or 1. Got: {invalid_sa_accept}")

        if key_norm == "comm":
            invalid_comm = [v for v in parsed_values if v not in (0, 1)]
            if invalid_comm:
                parser.error(f"--sweep comm only accepts 0 or 1 (inter-colony communication off/on). Got: {invalid_comm}")

        if key_norm in per_param_values:
            per_param_values[key_norm].extend(parsed_values)
        else:
            per_param_values[key_norm] = parsed_values

    keys = sorted(per_param_values.keys())
    value_lists = [per_param_values[k] for k in keys]
    return [dict(zip(keys, values)) for values in itertools.product(*value_lists)]


def prepare_run_args(ns: argparse.Namespace) -> None:
    """Fill None-valued solver fields from algorithm-dependent defaults (MCAS = alg 2)."""
    if ns.alg == 2:
        if ns.timeout is None:
            ns.timeout = 180.0
        if ns.ants is None:
            ns.ants = 25
        if ns.threads is None:
            ns.threads = 4
        if ns.q0 is None:
            ns.q0 = 0.7
        if ns.rho is None:
            ns.rho = 0.7
        if ns.evap is None:
            ns.evap = 0.0075
        if ns.xi is None:
            ns.xi = 0.5
        if ns.safreq is None:
            ns.safreq = 25
        if ns.sa_tinit is None:
            ns.sa_tinit = 5.75
        if ns.comm_early is None:
            ns.comm_early = 60
        if ns.comm_late is None:
            ns.comm_late = 25
        if ns.comm_threshold is None:
            ns.comm_threshold = 100
    else:
        if ns.timeout is None:
            ns.timeout = 120.0
        if ns.ants is None:
            ns.ants = 10
        if ns.threads is None:
            ns.threads = 4
        if ns.q0 is None:
            ns.q0 = 0.9
        if ns.rho is None:
            ns.rho = 0.9
        if ns.evap is None:
            ns.evap = 0.005
        if ns.xi is None:
            ns.xi = 0.1
        if ns.safreq is None:
            ns.safreq = 0
        if ns.sa_tinit is None:
            ns.sa_tinit = 1.5
        if ns.comm_early is None:
            ns.comm_early = 100
        if ns.comm_late is None:
            ns.comm_late = 10
        if ns.comm_threshold is None:
            ns.comm_threshold = 200


def build_output_path_for_sweep(base_output: str, sweep_idx: int, total_sweeps: int, overrides: Dict[str, object]) -> str:
    if total_sweeps <= 1:
        return base_output

    base_path = Path(base_output)
    suffix_parts = [f"{k}-{str(v).replace('.', 'p')}" for k, v in sorted(overrides.items())]
    suffix = "__".join(suffix_parts) if suffix_parts else f"run-{sweep_idx + 1}"
    filename = f"{base_path.stem}__{suffix}{base_path.suffix}"
    return str(base_path.with_name(filename))


def execute_run(args: argparse.Namespace) -> int:
    # Process fixed_percentages to handle both space-separated and comma-separated formats
    if args.fixed_percentages:
        processed_percentages = []
        for value in args.fixed_percentages:
            # Split by comma if present, then convert to int
            if ',' in value:
                # Comma-separated values
                for v in value.split(','):
                    v = v.strip()
                    if v:
                        try:
                            processed_percentages.append(int(v))
                        except ValueError:
                            print(f"Warning: '{v}' is not a valid integer, skipping.", file=sys.stderr)
            else:
                # Space-separated value (single value)
                try:
                    processed_percentages.append(int(value))
                except ValueError:
                    print(f"Warning: '{value}' is not a valid integer, skipping.", file=sys.stderr)
        args.fixed_percentages = processed_percentages if processed_percentages else None

    repo_root = find_repo_root()

    try:
        solver_path = resolve_solver_path(args.solver)
    except FileNotFoundError as exc:
        print(exc, file=sys.stderr)
        return 1

    # Determine which instances to run
    if args.instances_root is not None:
        # User specified a specific folder
        instances_root = (repo_root / args.instances_root).resolve()
        try:
            instance_files = list(iter_instance_files(instances_root))
        except FileNotFoundError as exc:
            print(exc, file=sys.stderr)
            return 1
        if not instance_files:
            print(f"No instances found in '{instances_root}'.", file=sys.stderr)
            return 1
        instances_root_display = instances_root
    else:
        # Default: run both general and logic-solvable instances
        try:
            instance_files = list(iter_all_instance_files(repo_root))
        except FileNotFoundError as exc:
            print(exc, file=sys.stderr)
            return 1
        instances_root_display = repo_root / "instances" / "(general + logic-solvable + 16x16-database)"

    metadata_list = sort_instance_metadata([parse_metadata(path) for path in instance_files])

    if args.puzzle_sizes:
        allowed_sizes = set(args.puzzle_sizes)
        metadata_list = [meta for meta in metadata_list if meta.size_label in allowed_sizes]

    if args.fixed_percentages:
        allowed_fixed = set(args.fixed_percentages)
        metadata_list = [
            meta
            for meta in metadata_list
            if meta.fixed_percentage is not None and meta.fixed_percentage in allowed_fixed
        ]

    if args.range_start is not None or args.range_end is not None:
        def in_range(meta: InstanceMetadata) -> bool:
            stem = meta.path.stem
            if args.range_start is not None and stem < args.range_start:
                return False
            if args.range_end is not None and stem > args.range_end:
                return False
            return True
        metadata_list = [meta for meta in metadata_list if in_range(meta)]

    if not metadata_list:
        msg = "No instances match the specified filters."
        if args.range_start is not None or args.range_end is not None:
            msg += f" (range: {args.range_start or '…'} .. {args.range_end or '…'})"
        print(msg, file=sys.stderr)
        return 1

    if args.limit is not None:
        metadata_list = metadata_list[: args.limit]

    group_rows: List[dict] = []
    total_instances = len(metadata_list)
    current_group_key: Optional[Tuple[str, Optional[int]]] = None
    group_stats = {"total": 0, "successes": 0, "fails": 0, "times": [], "iterations": [], "idle_times": [], "comm_sessions": [], "with_comm": 0, "without_comm": 0}
    overall_total = 0
    overall_successes = 0
    overall_times: List[float] = []
    overall_iterations: List[int] = []
    overall_idle_times: List[float] = []
    overall_comm_sessions: List[int] = []
    overall_with_comm = 0
    overall_without_comm = 0

    for idx, metadata in enumerate(metadata_list, start=1):
        # Determine if this is a logic-solvable instance (no fixed_percentage)
        # 16x16-database instances can be configured separately
        is_16x16_database = "16x16-database" in str(metadata.path)
        is_9x9_database = "9x9-database" in str(metadata.path)
        is_25x25_database = "25x25-database" in str(metadata.path)
        is_logic_solvable = (
            metadata.fixed_percentage is None
            and not is_16x16_database
            and not is_9x9_database
            and not is_25x25_database
        )

        # Determine number of runs based on instance type
        if is_16x16_database:
            num_runs = args.database16x16_runs
        elif is_9x9_database:
            num_runs = args.database9x9_runs
        elif is_25x25_database:
            num_runs = args.database25x25_runs
        elif is_logic_solvable:
            num_runs = args.logic_runs
        else:
            num_runs = 1  # General instances always run once

        # Group key for statistics
        # For *-database (9x9, 16x16, 25x25), each instance gets its own group -> one CSV row per instance.
        # For other instances, group by (size_label, fixed_percentage).
        if is_16x16_database or is_25x25_database or is_9x9_database:
            instance_identifier = metadata.instance_id if metadata.instance_id is not None else metadata.relative_path
            group_key = (metadata.size_label, metadata.fixed_percentage, instance_identifier)
        else:
            group_key = (metadata.size_label, metadata.fixed_percentage)

        # If group key changed, summarize the previous group
        if current_group_key is not None and group_key != current_group_key:
            # Extract instance info from previous group key for per-instance (database) format
            prev_instance_id = None
            prev_instance_path = None
            if len(current_group_key) == 3:  # database per-instance format
                if isinstance(current_group_key[2], int):
                    prev_instance_id = current_group_key[2]
                else:
                    prev_instance_path = str(current_group_key[2])
            row = summarize_group(current_group_key[0], current_group_key[1], group_stats, args, prev_instance_id, prev_instance_path)
            if row:
                group_rows.append(row)
            group_stats = {"total": 0, "successes": 0, "fails": 0, "times": [], "iterations": [], "idle_times": [], "comm_sessions": [], "with_comm": 0, "without_comm": 0}

        if current_group_key is None:
            current_group_key = group_key
        elif group_key != current_group_key:
            current_group_key = group_key

        # Run the puzzle num_runs times (100 for logic-solvable, 1 for general)
        for run_num in range(1, num_runs + 1):
            cmd = build_solver_command(solver_path, metadata.path, repo_root, args)
            # Show progress for algorithm 2 when verbose is enabled
            show_progress = args.verbose and args.alg == 2
            result = run_solver(cmd, repo_root, timeout=args.solver_timeout, show_progress=show_progress)

            success, solve_time, iterations, communication, idle_time_avg, comm_sessions, stdout_text, stderr_text = parse_solver_output(result.stdout, result.stderr if result.stderr else "")

            if success is False and (solve_time is None or solve_time == 0.0):
                solve_time = round(float(args.timeout), 5)

            if args.verbose:
                status = "OK" if success else "FAIL" if success is not None else "UNKNOWN"
                if is_logic_solvable:
                    # For logic-solvable, show run number
                    if solve_time is not None:
                        if iterations is not None:
                            if idle_time_avg is not None and args.alg == 2:
                                print(f"[{metadata.size_label} run {run_num}/{num_runs}] {metadata.relative_path} -> {status} ({solve_time:.5f}s, {iterations} iter, idle_avg={idle_time_avg:.5f}s)")
                            else:
                                print(f"[{metadata.size_label} run {run_num}/{num_runs}] {metadata.relative_path} -> {status} ({solve_time:.5f}s, {iterations} iter)")
                        else:
                            if idle_time_avg is not None and args.alg == 2:
                                print(f"[{metadata.size_label} run {run_num}/{num_runs}] {metadata.relative_path} -> {status} ({solve_time:.5f}s, idle_avg={idle_time_avg:.5f}s)")
                            else:
                                print(f"[{metadata.size_label} run {run_num}/{num_runs}] {metadata.relative_path} -> {status} ({solve_time:.5f}s)")
                    else:
                        print(f"[{metadata.size_label} run {run_num}/{num_runs}] {metadata.relative_path} -> {status}")
                else:
                    # For general instances, show normal format
                    if solve_time is not None:
                        if iterations is not None:
                            if idle_time_avg is not None and args.alg == 2:
                                print(f"[{idx}/{total_instances}] {metadata.relative_path} -> {status} ({solve_time:.5f}s, {iterations} iter, idle_avg={idle_time_avg:.5f}s)")
                            else:
                                print(f"[{idx}/{total_instances}] {metadata.relative_path} -> {status} ({solve_time:.5f}s, {iterations} iter)")
                        else:
                            if idle_time_avg is not None and args.alg == 2:
                                print(f"[{idx}/{total_instances}] {metadata.relative_path} -> {status} ({solve_time:.5f}s, idle_avg={idle_time_avg:.5f}s)")
                            else:
                                print(f"[{idx}/{total_instances}] {metadata.relative_path} -> {status} ({solve_time:.5f}s)")
                    else:
                        print(f"[{idx}/{total_instances}] {metadata.relative_path} -> {status}")

            group_stats["total"] += 1
            if success:
                group_stats["successes"] += 1
            else:
                group_stats["fails"] += 1
            # Only include times and iterations from successful runs in statistics
            if success and solve_time is not None:
                group_stats["times"].append(solve_time)
                if iterations is not None:
                    group_stats["iterations"].append(iterations)
                if idle_time_avg is not None and args.alg == 2:
                    group_stats["idle_times"].append(idle_time_avg)
                if comm_sessions is not None and args.alg == 2:
                    group_stats["comm_sessions"].append(comm_sessions)
                if communication is not None:
                    if communication:
                        group_stats["with_comm"] += 1
                    else:
                        group_stats["without_comm"] += 1

            overall_total += 1
            if success:
                overall_successes += 1
            # Only include times and iterations from successful runs in statistics
            if success and solve_time is not None:
                overall_times.append(solve_time)
                if iterations is not None:
                    overall_iterations.append(iterations)
                if idle_time_avg is not None and args.alg == 2:
                    overall_idle_times.append(idle_time_avg)
                if comm_sessions is not None and args.alg == 2:
                    overall_comm_sessions.append(comm_sessions)
                if communication is not None:
                    if communication:
                        overall_with_comm += 1
                    else:
                        overall_without_comm += 1

    output_path = (repo_root / args.output).resolve()
    if current_group_key is not None:
        # Extract instance info from group key for per-instance (database) format
        final_instance_id = None
        final_instance_path = None
        if len(current_group_key) == 3:  # database per-instance format
            if isinstance(current_group_key[2], int):
                final_instance_id = current_group_key[2]
            else:
                final_instance_path = str(current_group_key[2])
        row = summarize_group(current_group_key[0], current_group_key[1], group_stats, args, final_instance_id, final_instance_path)
        if row:
            group_rows.append(row)

    write_csv(output_path, group_rows)

    total, successes, avg_time = compute_summary(overall_total, overall_successes, overall_times)
    failures = total - successes
    avg_iterations = round(sum(overall_iterations) / len(overall_iterations), 2) if overall_iterations else None
    avg_idle = round(sum(overall_idle_times) / len(overall_idle_times), 5) if overall_idle_times else None
    avg_comm_sessions = round(sum(overall_comm_sessions) / len(overall_comm_sessions), 2) if overall_comm_sessions else None

    actual_ants = args.ants if args.ants is not None else (25 if args.alg == 2 else 10)
    actual_threads = args.threads if args.threads is not None else 4

    print("===== Summary =====")
    print(f"Solver binary   : {solver_path}")
    print(f"Instances folder: {instances_root_display}")
    if args.range_start is not None or args.range_end is not None:
        print(f"Instance range  : {args.range_start or '…'} .. {args.range_end or '…'}")
    print(f"Output CSV      : {output_path}")
    print(f"Algorithm       : {args.alg}")
    print(f"Ants            : {actual_ants}")
    if args.alg == 2:
        print(f"Threads         : {actual_threads}")
    print(f"q0              : {args.q0}")
    print(f"rho             : {args.rho}")
    print(f"bve             : {args.evap}")
    if args.alg == 0 or args.alg == 2:
        print(f"xi (local AC)   : {args.xi}")
        print(f"SA frequency    : {args.safreq} ({'enabled' if args.safreq > 0 else 'disabled'})")
        print(f"SA accept       : {args.sa_accept} ({'always accept (CP-like)' if args.sa_accept == 1 else 'conservative/hybrid'})")
        print(f"SA Tinit        : {args.sa_tinit}")
        print(f"SA Tmin         : {args.sa_tmin}")
        print(f"SA cooling      : {args.sa_cooling}")
    if args.alg == 2:
        print(f"commEarly       : {args.comm_early}")
        print(f"commLate        : {args.comm_late}")
        print(f"commThreshold   : {args.comm_threshold}")
        print(f"comm            : {args.comm} (1=inter-colony exchange on, 0=independent threads)")
    print(f"Timeout         : {args.timeout}s")
    print(f"Total puzzles   : {total}")
    print(f"Succeeded       : {successes}")
    print(f"Failed          : {failures}")
    if total:
        print(f"Average time    : {avg_time:.5f} s")
        if avg_iterations is not None:
            print(f"Average iters   : {avg_iterations:.2f}")
        if args.alg == 2 and avg_idle is not None:
            print(f"Average idle    : {avg_idle:.5f} s (avg barrier idle per communication session)")
        if args.alg == 2 and avg_comm_sessions is not None:
            print(f"Avg commSessions: {avg_comm_sessions:.2f}")
        if args.alg == 2 and (overall_with_comm > 0 or overall_without_comm > 0):
            print(f"With comm       : {overall_with_comm}/{overall_with_comm + overall_without_comm} ({(overall_with_comm / (overall_with_comm + overall_without_comm) * 100.0):.1f}%)")
    else:
        print(f"Average time    : n/a")

    sys.stdout.flush()  # Force immediate output to prevent timing issues

    return 0


def summarize_group(size_label: str, fixed_percentage: Optional[int], stats: dict, args: argparse.Namespace, instance_id: Optional[int] = None, instance_path: Optional[str] = None) -> dict:
    total = stats.get("total", 0)
    if total == 0:
        return {}

    successes = stats.get("successes", 0)
    fails = stats.get("fails", 0)
    times = stats.get("times", [])
    iterations = stats.get("iterations", [])
    idle_times = stats.get("idle_times", [])
    comm_sessions = stats.get("comm_sessions", [])
    with_comm = stats.get("with_comm", 0)
    without_comm = stats.get("without_comm", 0)
    success_rate = (successes / total) * 100.0 if total else 0.0
    average_time = round(sum(times) / len(times), 5) if times else 0.0
    time_std = round(statistics.pstdev(times), 5) if len(times) > 1 else 0.0
    average_iter = round(sum(iterations) / len(iterations), 2) if iterations else 0.0
    average_idle = round(sum(idle_times) / len(idle_times), 5) if idle_times else 0.0
    idle_std = round(statistics.pstdev(idle_times), 5) if len(idle_times) > 1 else 0.0
    average_comm_sessions = round(sum(comm_sessions) / len(comm_sessions), 2) if comm_sessions else 0.0
    comm_sessions_std = round(statistics.pstdev(comm_sessions), 2) if len(comm_sessions) > 1 else 0.0

    label = size_label
    if fixed_percentage is not None:
        label = f"{label} @ {fixed_percentage}% fixed"

    # Build summary message
    summary_msg = f"Summary {label}: success={successes}, fail={fails}, success_rate={success_rate:.2f}%, avg_time={average_time:.5f}s"
    
    if iterations:
        summary_msg += f", avg_iter={average_iter:.2f}"
    
    if args.alg == 2 and (with_comm > 0 or without_comm > 0):
        summary_msg += f", comm={with_comm}/{with_comm + without_comm}"

    if args.alg == 2 and idle_times:
        summary_msg += f", idle_avg={average_idle:.5f}s"
    if args.alg == 2 and comm_sessions:
        summary_msg += f", commSessions={average_comm_sessions:.2f}"
    
    print(summary_msg)
    sys.stdout.flush()  # Force immediate output to prevent timing issues

    actual_ants = args.ants if args.ants is not None else (25 if args.alg == 2 else 10)
    actual_threads = args.threads if args.threads is not None else 4

    return {
        "alg": args.alg,
        "puzzle_size": size_label,
        "f%": fixed_percentage if fixed_percentage is not None else "",
        "instance_id": instance_id if instance_id is not None else "",
        "instance_path": instance_path if instance_path is not None else "",
        "ants": actual_ants,
        "threads": actual_threads,
        "q0": args.q0,
        "rho": args.rho,
        "bve": args.evap,
        "xi": args.xi if (args.alg == 0 or args.alg == 2) else "",
        "safreq": args.safreq if (args.alg == 0 or args.alg == 2) else "",
        "saAccept": args.sa_accept if (args.alg == 0 or args.alg == 2) else "",
        "saTinit": args.sa_tinit if (args.alg == 0 or args.alg == 2) else "",
        "saTmin": args.sa_tmin if (args.alg == 0 or args.alg == 2) else "",
        "saCooling": args.sa_cooling if (args.alg == 0 or args.alg == 2) else "",
        "commEarly": args.comm_early if args.alg == 2 else "",
        "commLate": args.comm_late if args.alg == 2 else "",
        "commThreshold": args.comm_threshold if args.alg == 2 else "",
        "comm": args.comm if args.alg == 2 else "",
        "timeout": args.timeout,
        "success_rate": round(success_rate, 2),
        "time_mean": average_time,
        "time_std": time_std,
        "iter_mean": average_iter if (args.alg == 0 or args.alg == 1 or args.alg == 2) else "",
        "idle_mean": average_idle if args.alg == 2 else "",
        "idle_std": idle_std if args.alg == 2 else "",
        "commSessions_mean": average_comm_sessions if args.alg == 2 else "",
        "commSessions_std": comm_sessions_std if args.alg == 2 else "",
        "with_comm": with_comm if args.alg == 2 else "",
        "without_comm": without_comm if args.alg == 2 else "",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Run all general Sudoku instances through the solver.")
    parser.add_argument("--instances-root", default=None, help="Folder containing instances (default: runs instances/general, instances/logic-solvable, and instances/16x16-database)")
    parser.add_argument("--solver", default=None, help="Path to the solver executable (default: auto-detect)")
    parser.add_argument("--output", default="results/general_metrics.csv", help="Destination CSV file for metrics. Use a distinct path per run (e.g. results/9x9_range1.csv) to avoid overwriting.")
    parser.add_argument("--alg", type=int, default=0, help="Solver algorithm (0=ACS, 1=backtracking, 2=parallel ACS / MCAS).")
    parser.add_argument("--timeout", type=float, default=None, help="Timeout per puzzle in seconds (default: 120 for alg 0/1, 180 for alg 2).")
    parser.add_argument("--ants", type=int, default=None, help="Number of ants per colony (default: 10 for alg 0, 25 for alg 2).")
    parser.add_argument("--threads", type=int, default=None, help="Number of threads (parallel colonies) for alg 2 (default: 4).")
    parser.add_argument("--q0", type=float, default=None, help="ACS q0 (default: 0.9 alg 0, 0.7 alg 2).")
    parser.add_argument("--rho", type=float, default=None, help="ACS rho (default: 0.9 alg 0, 0.7 alg 2).")
    parser.add_argument("--evap", type=float, default=None, help="Best-so-far evaporation (default: 0.005 alg 0, 0.0075 alg 2).")
    parser.add_argument("--xi", type=float, default=None, help="ACS local pheromone ξ (default: 0.1 alg 0, 0.5 alg 2).")
    parser.add_argument("--safreq", type=int, default=None, help="SA every N iterations (default: 0 alg 0, 25 alg 2; 0 disables).")
    parser.add_argument("--saAccept", type=int, default=0, dest="sa_accept", choices=[0, 1], help="SA acceptance: 0=conservative/hybrid (default), 1=always accept SA result (CP-like). Applies to alg 0 and alg 2.")
    parser.add_argument("--saTinit", type=float, default=None, dest="sa_tinit", help="SA initial temperature (default: 1.5 alg 0, 5.75 alg 2).")
    parser.add_argument("--saTmin", type=float, default=0.01, dest="sa_tmin", help="SA stopping temperature (default: 0.01).")
    parser.add_argument("--saCooling", type=float, default=0.995, dest="sa_cooling", help="SA cooling rate per step (default: 0.995).")
    parser.add_argument("--commEarly", type=int, default=None, dest="comm_early", help="Parallel ACS (alg=2): early communication interval (default: 60).")
    parser.add_argument("--commLate", type=int, default=None, dest="comm_late", help="Parallel ACS (alg=2): late communication interval (default: 25).")
    parser.add_argument("--commThreshold", type=int, default=None, dest="comm_threshold", help="Parallel ACS (alg=2): switch early→late at this iteration (default: 100).")
    parser.add_argument(
        "--comm",
        type=int,
        default=1,
        choices=[0, 1],
        help="Parallel ACS (alg=2): 1=inter-colony communication on (default); 0=off—independent ACS threads, no barriers or exchange.",
    )
    parser.add_argument("--limit", type=int, default=None, help="Optional cap on number of instances to process.")
    parser.add_argument("--range-start", dest="range_start", default=None, help="Include only instances with stem >= this (e.g. 2020_00004 or 16x16_02203). Use with --range-end.")
    parser.add_argument("--range-end", dest="range_end", default=None, help="Include only instances with stem <= this (e.g. 2020_00483 or 16x16_02436). Use with --range-start.")
    parser.add_argument("--puzzle-size", dest="puzzle_sizes", nargs="+", choices=["9x9", "16x16", "25x25"], help="Filter by puzzle size(s), e.g. --puzzle-size 25x25.")
    parser.add_argument("--fixed-percentage", dest="fixed_percentages", type=str, nargs="+", help="Filter by fixed-cell percentage(s). Supports space-separated (e.g., --fixed-percentage 40 45 50) or comma-separated (e.g., --fixed-percentage 40,45,50).")
    parser.add_argument("--solver-timeout", type=float, default=None, help="Wall-clock timeout applied to each solver invocation.")
    parser.add_argument("--solver-verbose", action="store_true", help="Pass --verbose to the solver binary.")
    parser.add_argument("--verbose", action="store_true", default=True, help="Print per-instance progress to the console (default: True).")
    parser.add_argument("--no-verbose", dest="verbose", action="store_false", help="Disable per-instance progress output.")
    parser.add_argument("--logic-runs", type=int, default=100, help="Number of runs for logic-solvable instances (default: 100). 16x16-database instances always run once by default.")
    parser.add_argument(
        "--database16x16-runs",
        type=int,
        default=1,
        dest="database16x16_runs",
        help="Number of runs for 16x16-database instances (default: 1). Use --database16x16-runs 100 to run each puzzle 100 times.",
    )
    parser.add_argument(
        "--database9x9-runs",
        type=int,
        default=1,
        dest="database9x9_runs",
        help="Number of runs for 9x9-database instances (default: 1). Use --database9x9-runs 100 to run each puzzle 100 times.",
    )
    parser.add_argument(
        "--database25x25-runs",
        type=int,
        default=1,
        dest="database25x25_runs",
        help="Number of runs for 25x25-database instances (default: 1). Use --database25x25-runs 100 to run each puzzle 100 times.",
    )
    parser.add_argument(
        "--sweep",
        nargs="+",
        default=None,
        help=(
            "Parameter sweep in key=v1,v2 format. "
            "Examples: --sweep safreq=25,50,75,100 q0=0.8,0.9"
        ),
    )

    raw_args = parser.parse_args()
    sweep_overrides = parse_sweep_args(raw_args.sweep, parser)

    exit_code = 0
    total_sweeps = len(sweep_overrides)
    for idx, overrides in enumerate(sweep_overrides):
        run_args = argparse.Namespace(**vars(raw_args))
        for key, value in overrides.items():
            setattr(run_args, key, value)
        prepare_run_args(run_args)
        run_args.output = build_output_path_for_sweep(raw_args.output, idx, total_sweeps, overrides)

        if total_sweeps > 1:
            print(f"\n===== Sweep {idx + 1}/{total_sweeps} =====")
            if overrides:
                rendered = ", ".join(f"{k}={v}" for k, v in sorted(overrides.items()))
                print(f"Overrides       : {rendered}")
            print(f"Output CSV      : {run_args.output}")
            sys.stdout.flush()

        run_code = execute_run(run_args)
        if run_code != 0:
            exit_code = run_code

    return exit_code


if __name__ == "__main__":
    sys.exit(main())

