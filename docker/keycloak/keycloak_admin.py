"""Run the local Keycloak Admin CLI without exposing bootstrap credentials."""

import subprocess
from pathlib import Path


PROJECT = Path(__file__).resolve().parents[2]

# Credentials stay inside the container; CLI token files are removed after each call.
CLI = """
set -eu
config=$(mktemp)
trap 'rm -f "$config"' EXIT
cli=/opt/keycloak/bin/kcadm.sh
"$cli" config credentials --config "$config" --server http://localhost:8080 \
    --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" \
    --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null
"$cli" "$@" --config "$config"
"""


def command(*args, payload=None):
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", "keycloak", "sh", "-c", CLI, "--", *args],
        input=payload, capture_output=True, text=True, cwd=PROJECT,
    )
    if result.returncode:
        # Do not echo responses or environment variables containing credentials.
        raise RuntimeError(
            "Keycloak CLI failed. Check that Keycloak is running and its bootstrap admin "
            "credentials still match the administrator account. You can also perform the "
            "configuration manually in the Admin Console."
        )
    return result.stdout
