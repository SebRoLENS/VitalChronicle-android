#!/usr/bin/env python3
"""Copy the deterministic VitalChronicle desktop core into the Android Python source tree."""
from __future__ import annotations
import argparse, json, shutil, subprocess
from pathlib import Path

# __init__.py is platform-owned. The desktop initializer installs desktop-only
# patches and must never replace Android's minimal package bootstrap.
#
# Keep the reusable AI/agent implementation canonical in the desktop repository.
# Android owns only the platform adapters and inference engines.
CORE = [
    "analysis.py",
    "heart_rate_core.py",
    "ai_insights.py",
    "ai_pipeline.py",
    "ai_query_planner_core.py",
    "deterministic_detail_core.py",
    "agent_store.py",
    "agent_tools.py",
    "agent_tool_factory.py",
    "agent_tool_factory_reliability_patch.py",
    "agent_tool_factory_schema_guard.py",
    "agent_tool_factory_compat_patch.py",
    "agent_tool_factory_semantic_guard.py",
    "agent_tool_factory_semantic_compat_patch.py",
    "agent_tool_factory_english_patch.py",
    "agent_factory_request_preserve_patch.py",
    "agent_hard_query_reliability_patch.py",
    "agent_runtime_efficiency_patch.py",
    "agent_runtime_adaptive_patch.py",
    "constants.py",
    "i18n.py",
    "utils.py",
]

def main():
    p=argparse.ArgumentParser(); p.add_argument("--source",required=True); p.add_argument("--dest",required=True); p.add_argument("--assets",required=True); a=p.parse_args()
    source=Path(a.source).resolve(); package=source/"google_health_viewer"; dest=Path(a.dest).resolve()/"google_health_viewer"; dest.mkdir(parents=True,exist_ok=True)
    initializer=dest/"__init__.py"
    if not initializer.is_file(): raise SystemExit(f"Missing Android package initializer: {initializer}")
    for name in CORE:
        src=package/name
        if not src.is_file(): raise SystemExit(f"Missing shared core file: {src}")
        shutil.copy2(src,dest/name)
    # The hard-query module contains reusable deterministic primitives as well as
    # optional desktop runtime hooks. Android installs only the primitives: keep
    # the canonical source intact and make the synced copy importable without the
    # PySide-based desktop runtime.
    hard_query=dest/"agent_hard_query_reliability_patch.py"
    hard_text=hard_query.read_text(encoding="utf-8")
    desktop_import="from . import agent_runtime_v2 as runtime_v2\n"
    if desktop_import not in hard_text:
        raise SystemExit("Unexpected hard-query patch layout: desktop runtime import not found")
    hard_query.write_text(
        hard_text.replace(desktop_import, "runtime_v2 = None  # Android uses its Kotlin agent runtime.\n", 1),
        encoding="utf-8",
    )
    locales=package/"locales"
    if locales.is_dir():
        target=dest/"locales"; shutil.rmtree(target,ignore_errors=True); shutil.copytree(locales,target)
    try: revision=subprocess.check_output(["git","-C",str(source),"rev-parse","HEAD"],text=True).strip()
    except Exception: revision="unknown"
    assets=Path(a.assets); assets.mkdir(parents=True,exist_ok=True)
    (assets/"shared_core_revision.json").write_text(json.dumps({"repository":"SebRoLENS/VitalChronicle","revision":revision,"files":CORE},indent=2)+"\n")
    print(f"Synced VitalChronicle core {revision}")
if __name__=="__main__": main()
