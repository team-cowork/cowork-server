#!/usr/bin/env python3
"""Record that this image has no selected core-business unit tests."""
import json
import sys
from pathlib import Path

output, reason = sys.argv[1:]
if not reason.strip():
    raise SystemExit("A reason is required when no core-business test is selected")
path = Path(output)
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps({"status": "untested", "metric": "line", "covered": None,
                            "total": None, "reason": reason}, indent=2) + "\n")
