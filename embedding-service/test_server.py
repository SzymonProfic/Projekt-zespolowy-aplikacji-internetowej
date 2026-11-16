import json
import sys
import threading
import unittest
from http.client import HTTPConnection
from http.server import HTTPServer
from types import SimpleNamespace
from unittest.mock import Mock, patch

from server import MODEL_ID, MODEL_REVISION, create_handler, load_model


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.model = Mock()
        self.model.get_sentence_embedding_dimension.return_value = 768
        self.model.encode.return_value.tolist.return_value = [1.0] + [0.0] * 767

    def post(self, description):
        return self.request("POST", "/", description)

    def request(self, method, path, description=None):
        server = HTTPServer(("127.0.0.1", 0), create_handler(self.model))
        thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True)
        thread.start()
        try:
            connection = HTTPConnection("127.0.0.1", server.server_port, timeout=2)
            try:
                body = None if description is None else json.dumps({"description": description}, ensure_ascii=False).encode("utf-8")
                connection.request(method, path, body, {"Content-Type": "application/json"})
                response = connection.getresponse()
                return response.status, json.loads(response.read())
            finally:
                connection.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_loads_exact_pinned_model_revision(self):
        constructor = Mock(return_value=self.model)
        with patch.dict(sys.modules, {"sentence_transformers": SimpleNamespace(SentenceTransformer=constructor)}):
            self.assertIs(load_model(), self.model)
        constructor.assert_called_once_with(MODEL_ID, revision=MODEL_REVISION)

    def test_response_includes_vector_and_loaded_model_metadata(self):
        description = "zapytanie: Czarny portfel\nSkórzany, bez logo."
        status, response = self.post(description)
        self.assertEqual(status, 200)
        self.assertEqual(response["description"], description)
        self.assertEqual(response["model_id"], MODEL_ID)
        self.assertEqual(response["model_revision"], MODEL_REVISION)
        self.assertEqual(response["dimensions"], 768)
        self.assertEqual(len(response["numbers"]), response["dimensions"])
        self.assertEqual(response["numbers"], [1.0] + [0.0] * 767)
        self.model.encode.assert_called_once_with(description, normalize_embeddings=True)

    def test_rejects_unexpected_model_dimensions_before_serving(self):
        self.model.get_sentence_embedding_dimension.return_value = 1024
        with self.assertRaises(ValueError):
            create_handler(self.model)

    def test_model_endpoint_returns_metadata_without_running_inference(self):
        status, response = self.request("GET", "/model")
        self.assertEqual(status, 200)
        self.assertEqual(response, {"model_id": MODEL_ID, "model_revision": MODEL_REVISION, "dimensions": 768})
        self.model.encode.assert_not_called()

    def test_unknown_get_endpoint_returns_404(self):
        status, response = self.request("GET", "/missing")
        self.assertEqual(status, 404)
        self.assertIn("error", response)
        self.model.encode.assert_not_called()

    def test_wrong_encoded_dimensions_return_error_instead_of_false_metadata(self):
        self.model.encode.return_value.tolist.return_value = [1.0, 0.0, 0.0]
        with self.assertLogs(level="ERROR"):
            status, response = self.post("Czarny portfel")
        self.assertEqual(status, 500)
        self.assertIn("error", response)
        self.assertNotIn("model_revision", response)


if __name__ == "__main__":
    unittest.main()
