#!/usr/bin/env python3
"""Summarize a single arbigent run's timing from arbigent-result/jsonls.

Usage: analyze_run.py [jsonls_dir]
Reports per Codex decision: duration, resumed/schema, action; plus inter-step
gap (non-model time: screenshot + UI hierarchy + annotation + action + wait).
"""
import json
import glob
import os
import sys

jsonls = sys.argv[1] if len(sys.argv) > 1 else "arbigent-result/jsonls"
files = glob.glob(os.path.join(jsonls, "*.jsonl"))
recs = []
for f in files:
    try:
        d = json.load(open(f))
    except Exception:
        continue
    if "startedAt" not in d:
        continue
    action = ""
    try:
        action = (json.loads(d.get("lastMessage") or "{}").get("action") or "")
    except Exception:
        action = "(unparsed)"
    recs.append({
        "startedAt": d["startedAt"],
        "finishedAt": d["finishedAt"],
        "durationMs": d.get("durationMs", 0),
        "resumed": d.get("resumed"),
        "schema": d.get("schemaEnforced"),
        "action": action,
        "exitCode": d.get("exitCode"),
    })

recs.sort(key=lambda r: r["startedAt"])
if not recs:
    print("No codex-response records found in", jsonls)
    sys.exit(0)

print(f"{'#':>2} {'codexMs':>8} {'gapMs':>7} {'resumed':>7} {'schema':>6} {'exit':>4}  action")
print("-" * 70)
total_codex = 0
total_gap = 0
prev_finish = None
for i, r in enumerate(recs):
    gap = (r["startedAt"] - prev_finish) if prev_finish is not None else 0
    if prev_finish is not None:
        total_gap += gap
    total_codex += r["durationMs"]
    prev_finish = r["finishedAt"]
    print(f"{i+1:>2} {r['durationMs']:>8} {gap:>7} {str(r['resumed']):>7} {str(r['schema']):>6} {str(r['exitCode']):>4}  {r['action']}")

wall = recs[-1]["finishedAt"] - recs[0]["startedAt"]
n = len(recs)
print("-" * 70)
print(f"steps={n}  wall(first→last codex)={wall/1000:.1f}s")
print(f"codex total={total_codex/1000:.1f}s  avg={total_codex/n/1000:.1f}s  max={max(r['durationMs'] for r in recs)/1000:.1f}s")
print(f"non-model gap total={total_gap/1000:.1f}s  avg/step={total_gap/max(1,n-1)/1000:.1f}s")
resumed_n = sum(1 for r in recs if r["resumed"])
schema_n = sum(1 for r in recs if r["schema"])
print(f"resumed={resumed_n}/{n}  schemaEnforced={schema_n}/{n}")
