#!/usr/bin/env python3
"""Manually rerun the same initializer used by Docker Compose."""

import subprocess
import sys
from pathlib import Path


if __name__ == "__main__":
    project = Path(__file__).resolve().parents[2]
    result = subprocess.run(
        ["docker", "compose", "run", "--rm", "keycloak-init"], cwd=project,
    )
    sys.exit(result.returncode)
