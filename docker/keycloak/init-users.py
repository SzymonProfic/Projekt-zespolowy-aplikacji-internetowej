#!/usr/bin/env python3
"""Provision missing development users through Keycloak's Admin REST API."""

import json
import os
import sys
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen


def request(url, *, data=None, token=None, form=False):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if data is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        data = (urlencode(data) if form else json.dumps(data)).encode("utf-8")
    with urlopen(Request(url, data=data, headers=headers), timeout=5) as response:
        body = response.read()
        return json.loads(body) if body else None


def wait_for_realm(base_url, realm):
    deadline = time.monotonic() + 120
    print("Waiting for Keycloak and realm import...", flush=True)
    while True:
        try:
            request(base_url + "/realms/" + realm + "/.well-known/openid-configuration")
            return
        except HTTPError as error:
            if error.code not in (404, 502, 503, 504):
                raise
        except (URLError, OSError):
            pass
        if time.monotonic() >= deadline:
            raise RuntimeError("Keycloak realm was not ready within 120 seconds")
        time.sleep(2)


def main():
    definition = json.loads(Path(__file__).with_name("foundly-realm.json").read_text())
    base_url = os.environ.get("KEYCLOAK_BASE_URL", "http://keycloak:8080").rstrip("/")
    realm = quote(definition["realm"], safe="")
    wait_for_realm(base_url, realm)
    token = request(base_url + "/realms/master/protocol/openid-connect/token", form=True, data={
        "grant_type": "password",
        "client_id": "admin-cli",
        "username": os.environ["KC_BOOTSTRAP_ADMIN_USERNAME"],
        "password": os.environ["KC_BOOTSTRAP_ADMIN_PASSWORD"],
    })["access_token"]
    users_url = base_url + "/admin/realms/" + realm + "/users"
    for user in definition.get("users", []):
        username = user["username"]
        query_url = users_url + "?" + urlencode({"username": username, "exact": "true"})
        if request(query_url, token=token):
            print(f"User {username} already exists; account and password unchanged.", flush=True)
            continue
        try:
            request(users_url, data=user, token=token)
        except HTTPError as error:
            # Another initializer may have created this exact user concurrently.
            if error.code != 409 or not request(query_url, token=token):
                raise
            print(f"User {username} already created concurrently; account unchanged.", flush=True)
        else:
            print(f"Development user {username} created.", flush=True)
    print("Keycloak development users initialized.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except HTTPError as error:
        print(f"Keycloak initialization failed (HTTP {error.code}). Check administrator credentials "
              "and realm configuration. Existing accounts were not modified.", file=sys.stderr)
        sys.exit(1)
    except (RuntimeError, ValueError, KeyError, OSError) as error:
        # Never print HTTP response bodies, tokens, passwords or environment contents.
        print(f"Keycloak initialization failed: {type(error).__name__}. "
              "Check connectivity, administrator environment and realm JSON.", file=sys.stderr)
        sys.exit(1)
