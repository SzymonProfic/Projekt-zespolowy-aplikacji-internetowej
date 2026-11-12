"""Local HTTP service for generating text embeddings."""

import json
import logging
import os
from http.server import BaseHTTPRequestHandler, HTTPServer


def create_handler(model):
    class MyServer(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path != "/":
                self.send_json(404, {"error": "Nie znaleziono endpointu."})
                return
            if self.headers.get_content_type() != "application/json":
                self.send_json(415, {"error": "Użyj Content-Type: application/json."})
                return

            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0:
                    raise ValueError("Empty body")
                payload = json.loads(self.rfile.read(length).decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                self.send_json(400, {"error": "Body musi zawierać poprawny JSON UTF-8."})
                return

            req = payload.get("description") if isinstance(payload, dict) else None
            if not isinstance(req, str) or not req.strip():
                self.send_json(400, {"error": "Pole description musi być niepustym tekstem."})
                return

            try:
                embeddings = model.encode(
                    req, truncate_dim=1024, normalize_embeddings=True
                )
            except Exception:
                logging.exception("Nie udało się wygenerować embeddingu")
                self.send_json(500, {"error": "Nie udało się wygenerować embeddingu."})
                return

            self.send_json(200, {"description": req, "numbers": embeddings.tolist()})

        def send_json(self, status, response):
            body = json.dumps(response, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    return MyServer


def main():
    from sentence_transformers import SentenceTransformer

    logging.basicConfig(level=logging.INFO)
    model = SentenceTransformer("sdadas/mmlw-roberta-base")
    host = os.environ.get("HOST", "localhost")
    with HTTPServer((host, 8000), create_handler(model)) as server:
        print(f"Serwer działa na http://{host}:8000", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\nSerwer zatrzymany.", flush=True)


if __name__ == "__main__":
    main()
