#!/usr/bin/env python3
"""Reproducible local observations; requires Python, SymPy and JDK, never a C compiler."""
import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import platform
import random
import re
import select
import signal
import statistics
import subprocess
import sys
import time

import sympy as sp
from sympy.parsing.sympy_parser import parse_expr

ROOT = Path(__file__).resolve().parents[1]
SYMBOLS = {name: sp.Symbol(name, real=True) for name in "x y z a b c d t".split()}
LOCAL = dict(SYMBOLS, sin=sp.sin, cos=sp.cos, ln=sp.log, exp=sp.exp)


def parse(text):
    if not re.fullmatch(r"[A-Za-z0-9_()+*/^\s-]+", text):
        raise ValueError("Expression outside frozen corpus syntax")
    if any(name not in LOCAL for name in re.findall(r"[A-Za-z_][A-Za-z_0-9]*", text)):
        raise ValueError("Unknown corpus identifier")
    return parse_expr(text.replace("^", "**"), local_dict=LOCAL,
                      global_dict={"__builtins__": {}, "Integer": sp.Integer, "Symbol": sp.Symbol})


def text_nodes(text):
    tree = ast.parse(text.replace("^", "**"), mode="eval")
    return sum(isinstance(node, (ast.BinOp, ast.UnaryOp, ast.Call, ast.Name, ast.Constant))
               for node in ast.walk(tree)) - sum(isinstance(node, ast.Call) for node in ast.walk(tree))


class Worker:
    def __init__(self, directory):
        self.directory = directory
        self.process = None
        self.log = (directory / "java-stderr.log").open("a")

    def start(self):
        self.process = subprocess.Popen([os.environ.get("JAVA", "java"), "-cp", str(ROOT / "bin"),
                                         "lang.ExperimentWorker"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=self.log, text=True, bufsize=1, start_new_session=True)

    def stop(self):
        if self.process is not None:
            if self.process.poll() is None:
                os.killpg(self.process.pid, signal.SIGKILL)
            self.process.wait(timeout=5)
            self.process.stdin.close()
            self.process.stdout.close()
            self.process = None

    def request(self, case, engine):
        if self.process is None:
            self.start()
        try:
            self.process.stdin.write(f"{case['id']}\t{engine}\t{case['expression']}\n")
            self.process.stdin.flush()
            if not select.select([self.process.stdout], [], [], 15)[0]:
                self.stop()
                return {"status": "TIMEOUT", "error": "Java outer request deadline"}
            line = self.process.stdout.readline()
            if not line:
                raise RuntimeError("Java worker exited")
            result = json.loads(line)
            if result["id"] != case["id"]:
                raise RuntimeError("Mismatched worker response")
            return result
        except (OSError, RuntimeError, ValueError) as error:
            self.stop()
            return {"status": "ERROR", "error": str(error)}

    def close(self):
        self.stop()
        self.log.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--warmups", type=int, default=2)
    parser.add_argument("--repeats", type=int, default=7)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.warmups < 0 or args.repeats < 2:
        parser.error("Need nonnegative warmups and at least two repetitions")
    directory = args.output.resolve()
    directory.mkdir(parents=True, exist_ok=False)
    sources = sorted((ROOT / "src/lang").glob("*.java"))
    subprocess.run([os.environ.get("JAVAC", "javac"), "-d", str(ROOT / "bin"), *map(str, sources)], check=True)
    corpus_path = ROOT / "experiments/cases.json"
    corpus = json.loads(corpus_path.read_text())
    cases = corpus["cases"]
    source_paths = [*sources, ROOT / "experiments/run_study.py", ROOT / "experiments/PROTOCOL.md", corpus_path]
    hashes = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in source_paths}
    version = subprocess.run([os.environ.get("JAVA", "java"), "-version"], capture_output=True, text=True, check=True)
    metadata = {"format": "symsat-local-study-v1", "scope": corpus["scope"],
                "warmups": args.warmups, "repeats": args.repeats, "seed": 20261021,
                "python": sys.version, "sympy": sp.__version__, "java": version.stderr.strip(),
                "platform": platform.platform(), "machine": platform.machine(),
                "processor": platform.processor(), "source_sha256": hashes,
                "protocol": "experiments/PROTOCOL.md", "complete": False}
    (directory / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    worker = Worker(directory)
    rng = random.Random(metadata["seed"])
    records, warmup_errors = [], []
    oracle_cache = {}
    engines = ["egraph", "local_greedy", "sympy"]
    try:
        with (directory / "raw.jsonl").open("w") as raw:
            for index, case in enumerate(cases):
                original = parse(case["expression"])
                if sp.simplify(original - parse(case["reference"])) != 0:
                    raise AssertionError("Incorrect corpus reference: " + case["id"])
                ordered = engines[index % 3:] + engines[:index % 3]

                def solve(engine):
                    if engine != "sympy":
                        return worker.request(case, engine)
                    start = time.perf_counter_ns()
                    result = sp.simplify(original)
                    elapsed = time.perf_counter_ns() - start
                    return {"status": "OK", "elapsed_ns": elapsed,
                            "expression": str(result).replace("log(", "ln(")}

                for engine in ordered:
                    for _ in range(args.warmups):
                        try:
                            result = solve(engine)
                        except Exception as error:
                            result = {"status": "ERROR", "error": str(error)}
                        if result["status"] != "OK":
                            warmup_errors.append(dict(result, case=case["id"], engine=engine))
                for repeat in range(args.repeats):
                    order = list(ordered)
                    rng.shuffle(order)
                    for engine in order:
                        try:
                            result = solve(engine)
                        except Exception as error:
                            result = {"status": "ERROR", "error": str(error)}
                        row = dict(result, case=case["id"], family=case["family"], engine=engine, repeat=repeat)
                        if row["status"] == "OK":
                            key = (case["id"], row["expression"])
                            if key not in oracle_cache:
                                oracle_cache[key] = bool(sp.simplify(parse(row["expression"]) - original) == 0)
                            row["verified_equivalent"] = oracle_cache[key]
                            row["text_ast_nodes"] = text_nodes(row["expression"])
                        records.append(row)
                        raw.write(json.dumps(row) + "\n")
                        raw.flush()
                print(f"{index + 1}/{len(cases)} {case['id']}", flush=True)
    finally:
        worker.close()
    summaries = []
    for case in cases:
        for engine in engines:
            rows = [r for r in records if r["case"] == case["id"] and r["engine"] == engine]
            good = [r for r in rows if r["status"] == "OK"]
            durations = [r["elapsed_ns"] for r in good]
            q1, _, q3 = statistics.quantiles(durations, n=4, method="inclusive") if len(durations) >= 2 else (None, None, None)
            summaries.append({"case": case["id"], "engine": engine, "successful": len(good),
                              "errors": len(rows) - len(good), "median_ns": statistics.median(durations) if durations else None,
                              "q1_ns": q1, "q3_ns": q3, "input_text_nodes": text_nodes(case["expression"]),
                              "output_text_nodes": sorted(set(r["text_ast_nodes"] for r in good)),
                              "stop_reasons": sorted(set(r.get("search", {}).get("stop_reason", "NOT_REPORTED") for r in good))})
    incorrect = sum(r.get("verified_equivalent") is False for r in records)
    summary = {"cases": len(cases), "measured_observations": len(records), "correctness_failures": incorrect,
               "errors": sum(r["status"] != "OK" for r in records), "warmup_errors": warmup_errors,
               "per_case": summaries}
    (directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    metadata["complete"] = True
    (directory / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps({k: v for k, v in summary.items() if k != "per_case"}))
    return 1 if incorrect else 0


if __name__ == "__main__":
    raise SystemExit(main())
