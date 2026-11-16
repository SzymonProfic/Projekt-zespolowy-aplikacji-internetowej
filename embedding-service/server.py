"""Local HTTP service for generating text embeddings."""

import json
import logging
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

MODEL_ID = "sdadas/mmlw-roberta-base"
MODEL_REVISION = "bdeb29a43c3d58af1e6ed9d1836703a1ddfbe1d7"
EXPECTED_DIMENSIONS = 768


def load_model():
    from sentence_transformers import SentenceTransformer

    return SentenceTransformer(MODEL_ID, revision=MODEL_REVISION)


def create_handler(model):
    dimensions = model.get_sentence_embedding_dimension()
    if dimensions != EXPECTED_DIMENSIONS:
        raise ValueError(f"Model musi zwracać {EXPECTED_DIMENSIONS} wymiarów, otrzymano {dimensions}")
    metadata = {"model_id": MODEL_ID, "model_revision": MODEL_REVISION, "dimensions": dimensions}

    class MyServer(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/model":
                self.send_json(200, metadata)
            else:
                self.send_json(404, {"error": "Nie znaleziono endpointu."})

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
                embeddings = model.encode(req, normalize_embeddings=True)
                numbers = embeddings.tolist()
                if len(numbers) != dimensions:
                    raise ValueError("Wymiar wygenerowanego embeddingu nie odpowiada modelowi")
            except Exception:
                logging.exception("Nie udało się wygenerować embeddingu")
                self.send_json(500, {"error": "Nie udało się wygenerować embeddingu."})
                return

            self.send_json(200, {
                "description": req,
                "numbers": numbers,
                **metadata,
            })

        def send_json(self, status, response):
            body = json.dumps(response, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    return MyServer


def main():
    logging.basicConfig(level=logging.INFO)
    model = load_model()
    host = os.environ.get("HOST", "localhost")
    with HTTPServer((host, 8000), create_handler(model)) as server:
        print(f"Serwer działa na http://{host}:8000", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\nSerwer zatrzymany.", flush=True)


if __name__ == "__main__":
    main()
