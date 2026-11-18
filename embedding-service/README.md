# Serwer embeddingów

Lokalny serwer HTTP generujący embeddingi tekstu za pomocą modelu
`sdadas/mmlw-roberta-base`.

Serwis ładuje przypiętą rewizję `bdeb29a43c3d58af1e6ed9d1836703a1ddfbe1d7`
przez parametr `revision` biblioteki Sentence Transformers. Identyfikator i rewizja
są zapisane w `server.py` i zwracane w odpowiedzi. Model musi deklarować 768
wymiarów; inny wymiar blokuje start serwera. Świadoma aktualizacja modelu wymaga
zmiany przypiętej rewizji i ponownego przeliczenia zapisanych embeddingów.

## Uruchomienie

Wymagany Python 3.10 lub nowszy.

```sh
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
python server.py
```

Przy pierwszym uruchomieniu biblioteka pobierze model z Hugging Face, więc
potrzebne jest połączenie z internetem. Model jest ładowany raz, przy starcie.
Serwer nasłuchuje na `http://localhost:8000`. Zatrzymasz go przez `Ctrl+C`.

## Docker

Obraz korzysta z PyTorch w wariancie CPU, bez zależności CUDA. Instalacja
pakietów podczas budowania ma limit bezczynności połączenia 120 sekund
i do 10 ponowień nawiązania połączenia.

### Docker Compose

Z głównego katalogu repozytorium uruchom:

```sh
docker compose up -d --build embedding-service
docker compose logs -f embedding-service
```

Serwis startuje również przy zwykłym `docker compose up -d` oraz z profilem
`--profile app`. API jest dostępne na hoście pod `http://localhost:8000`,
a dla innych usług w Compose pod `http://embedding-service:8000`.
Backend wywołuje API w tle z workera obsługującego zadania `matching_job`.

`GET /model` zwraca `model_id`, `model_revision` i `dimensions` bez wywołania
inferencji. Backend używa tego endpointu do sprawdzania aktualności zapisanych
embeddingów przed ewentualnym ponownym generowaniem wektora.

```sh
curl --fail http://localhost:8000/model
```

Compose przechowuje model w wolumenie `embedding_model_cache`. Pierwsze
uruchomienie wymaga internetu i może potrwać kilka minut. Kontrola gotowości
sprawdza otwarcie portu po załadowaniu modelu, z 10-minutowym okresem startowym.
Stan sprawdzisz przez `docker compose ps embedding-service`.

### Samodzielny kontener

Zbuduj obraz i uruchom kontener:

```sh
docker build -t embedding-service .
docker run --rm --init -p 127.0.0.1:8000:8000 \
  -v embedding-model-cache:/home/app/.cache/huggingface \
  embedding-service
```

API będzie dostępne pod `http://localhost:8000`. Pierwsze uruchomienie wymaga
połączenia z internetem, aby pobrać model. Wolumen `embedding-model-cache`
zachowuje pobrany model pomiędzy uruchomieniami kontenera. Serwer zaczyna
przyjmować żądania po załadowaniu modelu.

Kontener działa jako użytkownik bez uprawnień roota i ustawia `HOST=0.0.0.0`,
aby udostępnić serwer przez port kontenera. Przy uruchomieniu bez Dockera
domyślny adres nasłuchiwania to nadal `localhost`; można go zmienić zmienną `HOST`.

## Przykład

```sh
curl 'http://localhost:8000/' \
  -H 'Content-Type: application/json' \
  -d '{"description": "Ala ma kota"}'
```

Odpowiedź zawiera tekst, wektor i metadane modelu:

```json
{
  "description": "Ala ma kota",
  "numbers": [0.012, -0.034, 0.056],
  "model_id": "sdadas/mmlw-roberta-base",
  "model_revision": "bdeb29a43c3d58af1e6ed9d1836703a1ddfbe1d7",
  "dimensions": 768
}
```

Wektor powyżej jest skrócony do trzech przykładowych wartości. Faktyczna lista
`numbers` ma 768 elementów i jest znormalizowana do długości 1. Wymiar odpowiedzi
jest odczytywany z załadowanego modelu i sprawdzany względem wygenerowanego wektora.

Tekst przekazuj przez `POST /` jako JSON w kodowaniu UTF-8, w polu `description`.
Nie trzeba kodować tekstu jak w adresie URL. Pole musi być niepustym tekstem.

Niepoprawny JSON lub niepoprawne pole `description` zwracają HTTP 400.
Niepoprawny Content-Type zwraca HTTP 415, a inna ścieżka HTTP 404.
Błąd generowania embeddingu zwraca HTTP 500.

## Testy kontraktu

Z głównego katalogu repozytorium:

```sh
python3 -m unittest discover -s embedding-service -p 'test_*.py' -v
```

Testy używają lokalnego HTTP i atrapy modelu, bez pobierania wag ani zależności AI.
