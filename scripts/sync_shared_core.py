#!/usr/bin/env python3
"""Copy the deterministic VitalChronicle desktop core into the Android Python source tree."""
from __future__ import annotations
import argparse, json, shutil, subprocess
from pathlib import Path

CORE = ["__init__.py", "analysis.py", "heart_rate_core.py", "ai_insights.py", "ai_pipeline.py", "ai_query_planner_core.py", "constants.py", "i18n.py", "utils.py"]

def main():
    p=argparse.ArgumentParser(); p.add_argument("--source",required=True); p.add_argument("--dest",required=True); p.add_argument("--assets",required=True); a=p.parse_args()
    source=Path(a.source).resolve(); package=source/"google_health_viewer"; dest=Path(a.dest).resolve()/"google_health_viewer"; dest.mkdir(parents=True,exist_ok=True)
    for name in CORE:
        src=package/name
        if not src.is_file(): raise SystemExit(f"Missing shared core file: {src}")
        shutil.copy2(src,dest/name)
    locales=package/"locales"
    if locales.is_dir():
        target=dest/"locales"; shutil.rmtree(target,ignore_errors=True); shutil.copytree(locales,target)
    try: revision=subprocess.check_output(["git","-C",str(source),"rev-parse","HEAD"],text=True).strip()
    except Exception: revision="unknown"
    assets=Path(a.assets); assets.mkdir(parents=True,exist_ok=True)
    (assets/"shared_core_revision.json").write_text(json.dumps({"repository":"SebRoLENS/VitalChronicle","revision":revision,"files":CORE},indent=2)+"\n")
    print(f"Synced VitalChronicle core {revision}")
if __name__=="__main__": main()
