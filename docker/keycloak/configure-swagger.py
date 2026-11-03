#!/usr/bin/env python3
"""Add Swagger callbacks to an existing local realm without reimporting it."""

import json
import sys

from keycloak_admin import command

CALLBACK = "http://localhost:8080/swagger-ui/oauth2-redirect.html"
ORIGIN = "http://localhost:8080"


def main():
    clients = json.loads(command("get", "clients", "-r", "foundly", "-q", "clientId=foundly-frontend"))
    if len(clients) != 1:
        raise RuntimeError("Expected exactly one foundly-frontend client in realm foundly")
    client = clients[0]
    redirects = list(client.get("redirectUris") or [])
    origins = list(client.get("webOrigins") or [])
    if CALLBACK in redirects and ORIGIN in origins:
        print("Swagger callback and origin are already configured.")
        return
    if CALLBACK not in redirects:
        redirects.append(CALLBACK)
    if ORIGIN not in origins:
        origins.append(ORIGIN)
    command("update", "clients/" + client["id"], "-r", "foundly", "-f", "-",
            payload=json.dumps({"redirectUris": redirects, "webOrigins": origins}))
    print("Swagger callback and origin added. Existing accounts and client settings preserved.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
