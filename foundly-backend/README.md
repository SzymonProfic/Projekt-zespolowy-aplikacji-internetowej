# Foundly — REST API

Backend Java 21 / Spring Boot 4.1, PostgreSQL 18 + PostGIS + pgvector, migracje Flyway i uwierzytelnianie Keycloak. Model domeny opisuje [model.md](model.md). API nie korzysta już z H2.

## Uruchomienie lokalne

Z głównego katalogu repozytorium:

```bash
cp .env.example .env
# Ustaw własne hasła w .env przed pierwszym uruchomieniem.
docker compose up -d --build db keycloak-init
```

Keycloak jest dostępny pod `http://localhost:8081`. Przy pierwszym uruchomieniu importuje realm `foundly` z [konfiguracji](../docker/keycloak/foundly-realm.json). Administrator loguje się danymi `KEYCLOAK_ADMIN_USERNAME` / `KEYCLOAK_ADMIN_PASSWORD` z `.env`. W realmie `foundly` jest importowane konto demonstracyjne opisane poniżej. Można też zarejestrować dodatkowych użytkowników lub utworzyć ich w konsoli administratora. Import nie nadpisuje istniejącego realmu przy restartach.

Uruchomienie backendu w drugim terminalu, również z głównego katalogu:

```bash
set -a
source .env
set +a
cd foundly-backend
./mvnw spring-boot:run
```

Backend: `http://localhost:8080`. Migracje tworzą tabele, ograniczenia, słownik dwunastu kategorii i indeksy PostGIS. Domyślnie baza działa na porcie 5432; po zmianie `POSTGRES_PORT` ustaw także `DATABASE_URL=jdbc:postgresql://localhost:<port>/foundly` (z właściwą nazwą bazy).

Alternatywnie uruchom backend w kontenerze:

```bash
docker compose --profile app up -d --build
```

Compose wykorzystuje osobne bazy i wolumeny dla Foundly i Keycloak. Nowy `postgis_data` nie montuje ani nie usuwa starego `postgres_data` ze wcześniejszego szablonu. Przeniesienie dawnych danych wymaga jawnego eksportu/importu. Obraz PostGIS działa jako `linux/amd64`, na Apple Silicon przez emulację, zgodnie z [obsługiwanymi architekturami obrazu](https://github.com/postgis/docker-postgis). Konfiguracja Keycloak `start-dev` i lokalny HTTP służą pracy deweloperskiej.

Usługa `db` buduje [własny obraz](../docker/postgres/Dockerfile) na bazie PostGIS,
z pakietem `postgresql-18-pgvector` zgodnie z [instrukcją pgvector](https://github.com/pgvector/pgvector#apt).
Migracja Flyway `V4__enable_pgvector.sql` włącza rozszerzenie `vector` w bazie Foundly
przy starcie backendu, również dla istniejącej bazy. Po aktualizacji obrazu wykonaj
`docker compose up -d --build --wait db` i uruchom ponownie backend.
Dotychczasowy wolumen `postgis_data` jest zachowany. Rozszerzenia nie są dodawane
do osobnej bazy Keycloak.

Migracja `V5__create_report_embedding.sql` tworzy tabelę `report_embedding`:
jeden wektor `vector(768)` na zgłoszenie, identyfikator i rewizja modelu,
wersja przygotowania tekstu, hash SHA-256 wejścia oraz czas wygenerowania.
Embedding jest usuwany razem ze zgłoszeniem. Worker zapełnia tabelę automatycznie
po utworzeniu ogłoszenia, gdy usługa embeddingów jest dostępna.

`EmbeddingTextBuilder` przygotowuje tekst w wersji `v1`: tytuł, opis i opcjonalny
wiersz `Kolor: ...`, rozdzielone znakami nowej linii. Normalizuje Unicode do NFC
i porządkuje białe znaki wewnątrz pól. Dla LOST dodaje prefiks `zapytanie: `,
a dla FOUND pozostawia tekst bez prefiksu. Zwraca tekst, jego SHA-256 w UTF-8
oraz wersję przygotowania. Kategoria, lokalizacja i data nie wpływają na hash.
Komponent jest wywoływany przez workera po zapisaniu zgłoszenia i zadania.

`EmbeddingClient.generate(text)` wysyła `POST /` do istniejącego embedding-service
z JSON-em `{"description":"..."}`. Zwraca `EmbeddingResult`: niemodyfikowalną listę
`numbers`, `modelId`, `modelRevision` oraz `dimensions`. Sprawdza 768 skończonych
liczb i normę równą 1 z tolerancją 0,001. Wymaga też nazwy
`sdadas/mmlw-roberta-base`, pełnego 40-znakowego hasha rewizji oraz całkowitego
`dimensions` równego 768. Brak metadanych jest błędem kontraktu, bez podstawiania
domyślnej rewizji. Klient wysyła body UTF-8 z `Content-Length`, wymaganym przez serwer Python.

`EmbeddingClient.modelInfo()` odczytuje `GET /model`: nazwę, rewizję i wymiar
faktycznie załadowanego modelu, bez generowania embeddingu. Worker pobiera te
metadane przed sprawdzeniem cache; nie zakłada stałej rewizji w backendzie.

Przepływ przygotowania tekstu i wywołania AI w workerze:

```java
var prepared = textBuilder.build(type, content);
var result = embeddingClient.generate(prepared.text());
// result.numbers(), result.modelId(), result.modelRevision(), result.dimensions()
```

| Zmienna | Domyślna wartość lokalna | Znaczenie |
| --- | --- | --- |
| `EMBEDDING_BASE_URL` | `http://localhost:8000` | Adres serwisu; Compose ustawia `http://embedding-service:8000` |
| `EMBEDDING_CONNECT_TIMEOUT` | `2s` | Limit nawiązania połączenia |
| `EMBEDDING_READ_TIMEOUT` | `30s` | Limit oczekiwania na odczyt danych; nie jest limitem całego zadania |

`EmbeddingClientException.isRetryable()` odróżnia błędy przejściowe (problemy
sieciowe, timeout, HTTP 408/429/5xx) od błędów kontraktu i pozostałych statusów
HTTP. Klient nie ponawia żądań samodzielnie; retry obsługuje kolejka workera.
Wywołanie AI odbywa się w tle, poza transakcją HTTP. Integracja nie wymaga
działającego serwisu AI podczas startu backendu ani przy zapisie ogłoszenia.

`ReportEmbeddingRepository` korzysta z `JdbcTemplate` i udostępnia:

- `findByReportId(reportId)` — odczyt `Optional<ReportEmbedding>` z wektorem i metadanymi;
- `save(embedding)` — atomowy `INSERT ... ON CONFLICT (report_id) DO UPDATE`;
- `isCurrent(reportId, inputHash, modelId, modelRevision, preprocessingVersion)` —
  sprawdzenie zgodności zapisanych metadanych z wartościami podanymi przez wywołującego.

`ReportEmbedding` jest niemodyfikowalnym rekordem danych, bez mapowania JPA.
Repozytorium zapisuje i odczytuje pgvector przez parametryzowany SQL; nie wymaga
dodatkowego sterownika wektorowego. pgvector przechowuje liczby w pojedynczej
precyzji, więc odczyt wartości `double` może nieznacznie różnić się od wejścia.
Zapis embeddingu nie zmienia `version` ani pól zgłoszenia w tabeli `report`.

Repozytorium nie wywołuje AI; zapisuje wynik otrzymany przez workera.
`isCurrent()` nie odczytuje aktualnego opisu z `report` i nie blokuje późniejszych
zmian. Kontrolę rewizji ogłoszenia oraz zabezpieczenie przed zapisem spóźnionego
wyniku zapewnia `EmbeddingJobProcessor` w transakcji publikacji.

Migracja `V6__create_matching_job.sql` dodaje trwałą kolejkę analizy. Przy
`POST /api/reports` oraz zaakceptowanym `PUT /api/reports/{id}` backend zapisuje
ogłoszenie i zadanie `PENDING` w jednej
transakcji. `MatchingJobService.schedule(reportId, reportVersion)` wymaga już
aktywnej transakcji (`Propagation.MANDATORY`). Repozytorium używa tego samego
źródła danych co JPA, więc rollback wycofuje oba zapisy.

Zadanie zawiera `report_version` z istniejącego `Report.version` oraz
`pipeline_version=v2`. Unikalność `(report_id, report_version, pipeline_version)`
zapobiega duplikatom, również przy równoczesnym planowaniu. Ponowne zlecenie
tej samej analizy nie resetuje stanu, terminu ani liczby prób istniejącego zadania.
Usunięcie ogłoszenia usuwa też jego zadania przez `ON DELETE CASCADE`.

Tabela ma przygotowane pola prób, terminu wykonania, dzierżawy, tokenu wykonawcy
i ostatniego błędu. Statusy to `PENDING`, `PROCESSING`, `COMPLETED`, `DEAD` oraz
`SUPERSEDED`. Worker sprawdza wersję i aktywność zgłoszenia przed wywołaniem AI
oraz ponownie przed publikacją wyników. Nieaktualne zadanie otrzymuje `SUPERSEDED`.
Każdy zaakceptowany PUT zachowuje dotychczasowe wersjonowanie i zleca analizę
nowej wersji zgłoszenia, nawet jeśli tekst po normalizacji pozostaje taki sam.
Starsze zadania nie mogą zapisać wyników po edycji. Ogłoszenia zakończone nie
mogą być edytowane, a ich oczekujące zadania są oznaczane jako `SUPERSEDED`.

`MatchingJobService.claimNext()` jest gotowym mechanizmem przejmowania pracy.
W osobnej, krótkiej transakcji (`REQUIRES_NEW`) wybiera jedno zadanie procesu `v2`:
gotowe `PENDING` lub `PROCESSING` z wygasłą dzierżawą. Używa
`FOR UPDATE SKIP LOCKED`, ustawia `PROCESSING`, zwiększa `attempts` i nadaje nowy
`lock_token` oraz `locked_until`. Zwraca `Optional<ClaimedMatchingJob>`.
Brak kwalifikującego się zadania daje `Optional.empty()`.

`MATCHING_LEASE_DURATION` ustala długość dzierżawy (domyślnie `2m`). Dzierżawa
powinna obejmować czas wykonania zadania, w tym timeout AI. Przejęcie zatwierdza się
przed rozpoczęciem wolnej pracy; blokady bazy nie są utrzymywane podczas inferencji.
Zadania w przyszłości i inne wersje procesu są pomijane.

`MatchingJobService.complete(job)` wymaga aktywnej transakcji. Ustawia `COMPLETED`
i usuwa dzierżawę tylko przy zgodnym tokenie oraz niewygasłym `locked_until`.
Zwraca `false` dla starego tokenu, wygasłej dzierżawy i zadania już zakończonego.
`EmbeddingJobProcessor.publish()` zapisuje wynik oraz wykonuje `complete()`
w tej samej transakcji. Wynik `false` powoduje wyjątek i rollback zapisu embeddingu.

`MatchingScheduler` wywołuje `MatchingWorker.processNext()` automatycznie.
Pojedynczy scheduler wykonuje zadania kolejno. Wiele instancji backendu korzysta
ze wspólnej kolejki i dzierżaw. Worker czyta dane w krótkiej transakcji,
wywołuje AI bez transakcji, a następnie blokuje zgłoszenie do zapisu,
ponownie sprawdza jego wersję/status i publikuje wynik wraz z zakończeniem zadania.
W procesie `v2` status `COMPLETED` oznacza zapis lub ponowne wykorzystanie
embeddingu, obliczenie rankingu i zapis aktualnych dopasowań. Brak kandydatów
również jest poprawnym zakończeniem zadania. Powiadomienia nie są jeszcze tworzone.

| Zmienna | Domyślnie | Znaczenie |
| --- | --- | --- |
| `MATCHING_ENABLED` | `true` | Włączenie automatycznego schedulera |
| `MATCHING_POLL_INTERVAL` | `1s` | Opóźnienie po zakończeniu poprzedniego wywołania workera |
| `MATCHING_LEASE_DURATION` | `2m` | Długość dzierżawy |
| `MATCHING_RETRY_DELAY` | `5s` | Początkowy czas retry, rosnący wykładniczo z losowym rozrzutem, maksymalnie 5 minut |
| `MATCHING_MAX_ATTEMPTS` | `5` | Limit prób, wliczając odzyskane dzierżawy |

Błędy przejściowe wracają do `PENDING` z przyszłym `available_at`.
Błędy trwałe lub wyczerpanie limitu dają `DEAD`; również wygasłe dzierżawy
po ostatniej próbie są wycofywane do `DEAD`. Stan zmienia tylko posiadacz
aktualnego, niewygasłego tokenu. Błędy są zapisywane jako kody, bez tekstu
ogłoszenia, wektora lub surowej odpowiedzi AI.

Po przygotowaniu tekstu i pobraniu metadanych modelu worker sprawdza cache.
Zgodny hash, model, jego rewizja i wersja przygotowania tekstu pozwalają pominąć
`POST /` bez zmiany `report_embedding.updated_at`. Ranking i zapis dopasowań
wykonują się również w tej ścieżce.
Dotyczy to m.in. zmiany samej kategorii, daty, lokalizacji lub białych znaków
normalizowanych przez builder. Zmiana tytułu, opisu lub koloru wymagająca innego
tekstu wejściowego powoduje ponowną generację. Niezgodna rewizja modelu albo
wersja przygotowania tekstu również unieważnia cache.

Przy ponownym użyciu wektora `EmbeddingJobProcessor.completeIfCurrent()` blokuje
ogłoszenie, sprawdza jego wersję i aktywność oraz kończy zadanie wyłącznie
z aktualnym tokenem. Brak metadanych serwisu AI nie pozwala uznać cache za aktualny
na podstawie samego hasha. Aktualizacja modelu wymusi przeliczenie przy następnym
zadaniu; masowe ponowne zlecenie analizy starszych zgłoszeń pozostaje osobnym etapem.

Po uruchomieniu aktualnego backendu i utworzeniu ogłoszenia kolejkę można
sprawdzić w bazie:

```sql
SELECT id, report_id, report_version, pipeline_version, status, attempts, available_at
FROM matching_job
ORDER BY created_at DESC;

SELECT report_id, model_id, model_revision, vector_dims(embedding) AS dimensions, updated_at
FROM report_embedding
ORDER BY updated_at DESC;
```

Do ręcznego testu uruchom aktualne kontenery:

```sh
docker compose --profile app up -d --build backend embedding-service
```

Dodaj ogłoszenie przez aplikację. Zadanie powinno przejść z `PENDING` przez
`PROCESSING` do `COMPLETED`, a tabela `report_embedding` powinna otrzymać wpis.
Następnie zmień tylko datę lub lokalizację: pojawi się kolejne zadanie dla nowej
`report_version`, ale hash i `updated_at` embeddingu powinny pozostać bez zmian.
Po zmianie opisu zmienią się hash i czas embeddingu. Nowy backend wymaga również
nowego obrazu embedding-service obsługującego `GET /model`.
Przy `DEAD` sprawdź `last_error` i konfigurację usługi AI. Ustawienie
`MATCHING_ENABLED=false` wyłącza tylko automatyczne przetwarzanie; zapis zadań
przy publikacji pozostaje aktywny.

## Ranking kandydatów LOST/FOUND

`MatchingRankingService.rank(reportId, limit)` zwraca uporządkowaną listę
potencjalnych par. Limit musi mieścić się w przedziale 1–100. Jest to komponent
wewnętrzny, wywoływany automatycznie przez workera. Wynik utrwala
`ReportMatchService.saveIfCurrent()` opisany poniżej. Autor może odczytać zapisane
dopasowania przez `GET /api/reports/{id}/matches`. Nie ma jeszcze powiadomień.
Sam ranking nie zmienia danych.

`MatchingCandidateRepository` jednym zapytaniem pobiera spójny obraz obu zgłoszeń
i embeddingów. Uwzględnia tylko aktywne zgłoszenia przeciwnych typów, różnych
autorów, mające wektory z tej samej wersji modelu i przygotowania tekstu.
Semantykę oblicza jako `1 - (embedding <=> embedding)`, a odległość jako
`ST_Distance(geography, geography)` w metrach. Inna kategoria nie wyklucza pary.

Serwis ponownie buduje tekst każdego zgłoszenia przez `EmbeddingTextBuilder`
i porównuje jego hash oraz wersję przygotowania z zapisanymi metadanymi.
Zmiana opisu oczekująca na worker wyklucza nieaktualny wektor z rankingu.
Zmiana samej daty, kategorii lub lokalizacji pozwala użyć istniejącego embeddingu
z nowymi danymi rankingu. Nie kopiujemy normalizacji Unicode ani hashowania do SQL.

Brak źródła, nieaktywny stan lub brak aktualnego embeddingu źródła daje pustą
listę. Pusta lista nie rozróżnia oczekiwania na embedding od braku kandydatów;
docelowe API będzie potrzebować osobnego stanu analizy. Serwis nie wywołuje AI:
porównuje zgodne rewizje zapisane przy wektorach, bez sprawdzania, czy są najnowszą
rewizją wdrożonego modelu. API decyzji użytkowników pozostaje osobnym etapem.
Pary ze statusem `REJECTED` są już wykluczane w zapytaniu
przed sortowaniem i limitem, aby nie blokowały innych kandydatów.

### Składowe i konfiguracja

Parametry są zebrane w `MatchingRankingProperties`, pod prefiksem
`foundly.matching.ranking` w `application.properties`. Nieprawidłowe wagi, skale
lub próg zatrzymują uruchomienie konfiguracji.

| Parametr | Domyślnie | Znaczenie |
| --- | --- | --- |
| `version` | `v1` | Wersja reguł dołączana do wyniku |
| `semantic-weight` | `0.6` | Waga semantyki |
| `location-weight` | `0.2` | Waga lokalizacji |
| `date-weight` | `0.1` | Waga daty |
| `category-weight` | `0.1` | Waga kategorii |
| `distance-scale-meters` | `10000` | Skala spadku zgodności lokalizacji |
| `date-scale-days` | `14` | Skala spadku zgodności po dacie zgubienia |
| `early-found-tolerance-days` | `2` | Tolerancja znalezienia przed podaną datą zgubienia |
| `early-found-scale-days` | `2` | Skala spadku po przekroczeniu tej tolerancji |
| `min-semantic-score` | `0.5` | Minimalna semantyka, niezależna od pozostałych sygnałów |
| `max-results` | `10` | Liczba najlepszych ocen zapisywanych przez pojedyncze zadanie; zakres 1–100 |

Kalkulator stosuje następujące reguły:

- `S`: podobieństwo cosinusowe ograniczone do `[0,1]`.
- `L = exp(-distanceMeters / distanceScaleMeters)`.
- Dla `days = foundDate - lostDate >= 0`: `D = exp(-days / dateScaleDays)`.
- Dla ujemnego odstępu: `D = exp(-max(0, -days - tolerance) / earlyFoundScaleDays)`.
- `C = 1` przy tej samej znanej kategorii, `C = 0` przy różnych znanych kategoriach.
  `OTHER` i brak kategorii oznaczają brak wiarygodnego sygnału, a nie zgodność.

Dla kompletnych danych wynik to `0.6*S + 0.2*L + 0.1*D + 0.1*C`.
Przy brakujących sygnałach wynik jest dzielony przez sumę dostępnych wag.
`evidenceCoverage` pokazuje udział dostępnych wag w ich pełnej sumie.
Kalkulator obsługuje brak lokalizacji i daty, ale obecny kontrakt zapisu zgłoszeń
nadal wymaga tych pól; obsługa danych nieznanych i przedziałów wymaga rozszerzenia
schematu oraz formularzy.

Wynik `RankedMatch` ma kanoniczne `lostReportId` i `foundReportId`, wersje obu
zgłoszeń, hashe wejścia i czasy wygenerowania obu embeddingów, identyfikację
modelu i rankingu, odległość, odstęp dni oraz składowe
`scores`. Wagi, skale i próg są wartościami startowymi, bez kalibracji na oznaczonym
zbiorze par. `finalScore` jest rankingiem, a nie prawdopodobieństwem własności.

### Wyszukiwanie i testy

Dokładny ranking jest liczony dla wszystkich kwalifikujących się kandydatów.
Hash i próg semantyczny są sprawdzane przed końcowym sortowaniem i limitem.
Remisy rozstrzyga semantyka, następnie identyfikatory LOST i FOUND. Nie ma HNSW
ani wstępnego ograniczenia do kilku najbardziej podobnych wektorów, które mogłoby
zgubić parę lepszą według rankingu hybrydowego. To rozwiązanie dla początkowej,
niewielkiej skali; przed wzrostem liczby zgłoszeń potrzebne będą pomiary zapytań.

```java
var matches = matchingRankingService.rank(reportId, 10);
// match.scores().semantic(), location(), date(), category(), finalScore(), evidenceCoverage()
```

Testy kalkulatora oraz integracji z rzeczywistym PostgreSQL/PostGIS/pgvector:

```sh
cd foundly-backend
./mvnw -Dtest=MatchingScoreCalculatorTests,MatchingRankingIntegrationTests test
```

Testy integracyjne wymagają uruchomionego Dockera; przy jego braku są pomijane.
Wyniki są obrazem danych w chwili zapytania. Serwis zapisu dopasowań ponownie
weryfikuje aktualność zgłoszeń i metadanych wektorów.

Źródła operacji bazodanowych: [pgvector](https://github.com/pgvector/pgvector#querying),
[PostGIS ST_Distance](https://postgis.net/docs/ST_Distance.html).

## Zapis potencjalnych dopasowań

Migracja `V7__create_report_match.sql` tworzy `report_match` z jedną kanoniczną
parą LOST/FOUND (`UNIQUE(lost_report_id, found_report_id)`). Rekord zawiera
składowe i wynik rankingu, kompletność danych, odległość i odstęp dni, wersje
ogłoszeń, hashe wejściowe i czasy obu embeddingów, model, wersję przygotowania
tekstu i rankingu oraz status, daty i licznik `version`.

`ReportMatchService.saveIfCurrent(evaluation)` działa transakcyjnie i zwraca:

| Wynik | Znaczenie |
| --- | --- |
| `SAVED` | Utworzono parę lub zaktualizowano ocenę istniejącej pary |
| `UNCHANGED` | Ocena jest identyczna; nie zmieniono ID, dat ani wersji rekordu |
| `STALE` | Zgłoszenia/wektory zmieniły się, są nieaktywne, niezgodne lub brakuje danych |
| `PROTECTED` | Istniejąca para ma status `REJECTED` albo `CONFIRMED` |

Przed zapisem serwis blokuje oba zgłoszenia w kolejności UUID, weryfikuje typy,
różnych autorów, aktywność i wersje. Następnie blokuje do odczytu oba embeddingi
i sprawdza ich hashe, czasy wygenerowania oraz model i wersję przygotowania
tekstu. Sprawdza też wersję rankingu i minimalną semantykę. Dopiero potem blokuje
istniejące dopasowanie i wykonuje upsert. Sama zgodność wersji ogłoszenia nie
wystarcza: worker może wymienić wektor bez zmiany `Report.version`.

Ponowne zapisanie tej samej pary nie tworzy duplikatu. Nowa ocena zachowuje
`id` i `created_at`, aktualizuje dane i zwiększa `version`. Nieaktualny wynik
nie zastępuje nowszego. Wartości wersji rankingu trzeba zmieniać wraz ze zmianą
reguł lub wag; wszystkie instancje backendu powinny używać zgodnej konfiguracji.

Status początkowy to `PROPOSED`. Upsert może odświeżyć `PROPOSED` lub przywrócić
aktualnie ocenioną parę `EXPIRED` do `PROPOSED`. Nie zmienia `REJECTED` ani
`CONFIRMED`. Statusy decyzji są przygotowane w schemacie; API decyzji i osobne
decyzje obu autorów nie są jeszcze wdrożone.

Edycja oraz zakończenie zgłoszenia wygaszają jego pary `PROPOSED` i `CONFIRMED`
do `EXPIRED` w tej samej transakcji co zmiana zgłoszenia. Odrzucone pary pozostają
odrzucone. Usunięcie dowolnego zgłoszenia usuwa parę przez `ON DELETE CASCADE`.
Samo wygaśnięcie nie usuwa zapisanej oceny ani informacji o jej pochodzeniu.
Zmiana modelu, samodzielna wymiana wektora lub zmiana polityki rankingu wymaga
ponownej analizy; globalne unieważnianie takich ocen będzie częścią integracji
workera: przy publikacji wygasza on pary, których wersje, fingerprinty wektorów,
wersja rankingu lub minimalna semantyka przestały pasować. Odrzucone pary są
zachowane. Endpoint odczytu sprawdza aktualność sugestii niezależnie od workera.

Przykład użycia wewnętrznego:

```java
var evaluations = matchingRankingService.rank(reportId, 10);
for (var evaluation : evaluations) {
    var outcome = reportMatchService.saveIfCurrent(evaluation);
}
```

Pojedyncze wywołanie serwisu zapisuje jedną parę. Worker grupuje publikację
wektora, ranking, wygaszanie i zapis par oraz zakończenie zadania w jednej
transakcji. Utrata tokenu albo błąd zapisu wycofują wszystkie te efekty.
Dotyczy to zarówno nowego wektora, jak i ścieżki cache. Endpoint odczytu opisano
poniżej; powiadomienia pozostają kolejnym etapem. Flyway wykona V7 i V8 również
na istniejącej bazie.

### Publikacja i istniejące zgłoszenia

Przed publikacją worker pobiera transakcyjną blokadę doradczą PostgreSQL
`pg_advisory_xact_lock(724013, 1)`. Serializuje ona krótki fragment publikacji
między instancjami backendu. Drugi z równolegle generowanych wektorów widzi
pierwszy po jego zatwierdzeniu, więc dwa nowe ogłoszenia nie pomijają swojej pary.
Blokada nie obejmuje wywołania AI.

Worker blokuje źródło i kwalifikujące się ogłoszenia przeciwnego typu w kolejności
UUID przed rozpoczęciem zapisów. Dzięki temu późniejsze zapisy wielu par nie
odwracają kolejności blokad. Jest to rozwiązanie dla małej skali; przy dużej bazie
trzeba zmierzyć czas transakcji i wpływ na edycje. Poprawne istniejące pary, które
nie znalazły się w nowym top N, pozostają zapisane: limit dotyczy jednego zadania,
a nie całkowitej liczby rekordów powiązanych ze zgłoszeniem.

Migracja `V8__enqueue_matching_pipeline_v2.sql` zleca proces `v2` dla aktualnej
wersji każdego aktywnego ogłoszenia. Umożliwia analizę starszych zgłoszeń bez
ręcznej edycji i bez wymuszonego ponownego generowania zgodnych embeddingów.
Zakończona historia `v1` pozostaje w bazie, natomiast zadania `v1` w stanie
`PENDING` i `PROCESSING` otrzymują `SUPERSEDED` z unieważnionymi tokenami.
Wdrożenie zakłada restart backendu, bez równoległego utrzymywania starej aplikacji
zlecającej `v1` i nowej zlecającej `v2`. Późniejsze zmiany modelu lub rankingu
będą wymagały osobnego zlecenia ponownej analizy, nie ponownego uruchamiania V8.

Do testu ręcznego uruchom aktualny backend i embedding-service, dodaj podobne
LOST i FOUND z dwóch różnych kont i sprawdź `report_match`. Po zmianie samej
lokalizacji embedding zachowa hash i czas, ale zapisany ranking zostanie odświeżony.

```sql
SELECT id, lost_report_id, found_report_id, semantic_score, final_score,
       status, ranking_version, version, created_at, updated_at
FROM report_match
ORDER BY final_score DESC, id;
```

Testy `MatchingRankingIntegrationTests` obejmują zapis i aktualizację,
idempotencję, równoległe wywołania, rollback, odrzucanie starych wyników,
ochronę statusów oraz wygaszanie i usuwanie przez istniejący `ReportService`.

## Konto demonstracyjne

Przy pierwszym uruchomieniu nowej bazy Keycloak importuje użytkownika z sekcji `users` w [foundly-realm.json](../docker/keycloak/foundly-realm.json):

| Pole | Wartość |
| --- | --- |
| Realm | `foundly` |
| Login | `demo` |
| Hasło | `FoundlyDemo123!` |

Konto jest zwykłym użytkownikiem bez uprawnień administratora. Hasło nie jest tymczasowe, e-mail jest oznaczony jako zweryfikowany, a pierwsze logowanie nie wymaga zmiany hasła. To jawne dane demonstracyjne przeznaczone do lokalnego developmentu; plik realmu z tym kontem nie jest konfiguracją produkcyjną. Lokalny profil `AppUser` w Foundly powstanie po pierwszym `PUT /api/me` po zalogowaniu albo przy publikacji zgłoszenia jako zabezpieczenie dla klientów API.

Przy zwykłym `docker compose up -d` oraz `docker compose --profile app up -d --build` automatycznie uruchamia się usługa **`keycloak-init`**. Czeka do 120 sekund na gotowość Keycloak i zakończenie importu, następnie odczytuje sekcję `users` z pliku realmu i dodaje brakujące konta przez Admin REST API. Backend w Compose czeka na poprawne zakończenie inicjalizacji. Działa to także dla istniejącego realmu, mimo że sam Keycloak [pomija jego ponowny import](https://www.keycloak.org/server/importExport).

**Istniejące konto o tym loginie jest pomijane, jego hasło i ustawienia pozostają bez zmian** — w takim przypadku loguj się dotychczasowym hasłem. Inicjalizacja nie usuwa danych ani nie importuje ponownie całego realmu. Po poprawnym zakończeniu status kontenera `keycloak-init` to **Exited (0)**; jest to oczekiwane zachowanie usługi jednorazowej. Kolejne `compose up` ponownie uruchamia zakończony kontener; samo `compose restart keycloak` nie uruchamia inicjalizacji.

Nie potrzebujesz Pythona na komputerze ani dostępu kontenera do socketu Dockera. Inicjalizator działa w osobnym obrazie Python i korzysta z danych administratora `KEYCLOAK_ADMIN_USERNAME` / `KEYCLOAK_ADMIN_PASSWORD` z `.env`. Muszą odpowiadać aktualnemu kontu administratora — zmiana `.env` nie zmienia hasła już istniejącego konta. Błąd inicjalizacji blokuje start backendu w Compose; przyczynę sprawdzisz poleceniem:

```bash
docker compose logs keycloak-init
```

Aby uruchomić inicjalizację ponownie bez restartowania aplikacji:

```bash
docker compose run --rm keycloak-init
```

Dotychczasowe `python3 docker/keycloak/seed-users.py` pozostaje skrótem do tego polecenia. Jeśli wskazujesz konkretne usługi podczas `up`, użyj `docker compose up -d db keycloak-init` — uruchomienie samego `keycloak` nie uruchamia jego zależnej usługi inicjalizującej.

## Wygląd logowania i rejestracja

Realm `foundly` korzysta z motywu [Foundly](../docker/keycloak/themes/foundly/README.md): jasnego tła, zielonych przycisków i typografii spójnej z frontendem. Motyw obejmuje logowanie, rejestrację i pozostałe formularze logowania Keycloak. Domyślny język to polski; można przełączyć go na angielski.

Po pobraniu zmian wykonaj z głównego katalogu:

```bash
docker compose up -d keycloak-init
```

Compose montuje pliki motywu, a `keycloak-init` ustawia motyw, języki i włącza rejestrację także w istniejącym realmie. Konta i hasła pozostają zachowane. Nie trzeba usuwać wolumenów. Po zakończeniu inicjalizacji odśwież formularz logowania.

W aplikacji przycisk **Utwórz konto** otwiera bezpośrednio rejestrację Keycloak. Ten sam przycisk jest dostępny na formularzu logowania, również podczas autoryzacji ze Swaggera. Po rejestracji przez frontend użytkownik wraca do aplikacji, która synchronizuje profil przez `PUT /api/me`.

## Dokumentacja interaktywna

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`.
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`.
- OpenAPI YAML: `http://localhost:8080/v3/api-docs.yaml`.

Swagger obsługuje logowanie Keycloak przez **Authorization Code z PKCE S256**, bez ręcznego kopiowania tokenu:

1. Otwórz Swagger pod `http://localhost:8080/swagger-ui/index.html`.
2. Kliknij **Authorize**. Client ID to `foundly-frontend`, client secret pozostaw pusty; zakresy `openid`, `profile` i `email` są wstępnie wybrane.
3. Kliknij **Authorize** w oknie. Zaloguj się swoim użytkownikiem w realmie `foundly`.
4. Po powrocie zamknij okno autoryzacji i wykonaj `PUT /api/me` bez body przez **Try it out → Execute**. Potem `GET /api/me` odczyta profil bez jego zmiany. Swagger dołącza access token do chronionych żądań.

Tokeny pozostają w pamięci strony; po odświeżeniu lub wygaśnięciu tokenu autoryzuj się ponownie. Przycisk **Logout** w Swaggerze usuwa lokalną autoryzację; nie kończy sesji SSO w Keycloak.

Nowa instalacja Keycloak importuje callback `http://localhost:8080/swagger-ui/oauth2-redirect.html` i Web Origin `http://localhost:8080`. **Dla już istniejącego realmu** import przy restarcie jest pomijany. Z głównego katalogu projektu uruchom jednorazowo:

```bash
python3 docker/keycloak/configure-swagger.py
docker compose --profile app up -d --build backend
```

Skrypt wymaga Pythona 3 oraz działającej usługi Keycloak. Korzysta z danych administratora ustawionych w środowisku kontenera, dodaje wyłącznie callback i origin i zachowuje istniejące wpisy, użytkowników i ustawienia klienta. Można go uruchamiać ponownie. Jeśli hasło administratora zmieniono od czasu pierwszego uruchomienia, dodaj te wartości ręcznie: **realm foundly → Clients → foundly-frontend → Settings → Valid redirect URIs / Web origins → Save**.

Po zmianie publicznego adresu Swaggera trzeba odpowiednio ustawić `SWAGGER_REDIRECT_URL` oraz callback i origin w Keycloak. Swagger należy otwierać przez `localhost`, zgodnie z konfiguracją, a nie `127.0.0.1`.

Dokumentacja jest generowana z kodu przez [springdoc-openapi](https://springdoc.org/); opcję PKCE opisuje [dokumentacja Swagger UI](https://swagger.io/docs/open-source-tools/swagger-ui/usage/oauth2/).

## Uwierzytelnianie

Publiczny klient `foundly-frontend` używa Authorization Code Flow z PKCE S256. Dozwolone adresy powrotu to `http://localhost:5173/*` oraz dokładny callback Swaggera `http://localhost:8080/swagger-ui/oauth2-redirect.html`. W Postmanie można skonfigurować ten sam przepływ i callback w dozwolonym adresie (lub dodać konkretny callback do klienta w Keycloak). Domyślny zakres klienta `basic` zapewnia claim `sub`, a mapper audience dodaje `foundly-backend`. Bezpośrednie logowanie hasłem do token endpointu oraz konta techniczne tego klienta są wyłączone. Frontend korzysta już z `keycloak-js` i synchronizuje lokalny profil po logowaniu oraz odświeżeniu access tokenu. Lista kart nadal korzysta z danych demonstracyjnych.

Parametry OIDC:

| Parametr | Wartość lokalna |
| --- | --- |
| Issuer | `http://localhost:8081/realms/foundly` |
| Authorization URL | `http://localhost:8081/realms/foundly/protocol/openid-connect/auth` |
| Token URL | `http://localhost:8081/realms/foundly/protocol/openid-connect/token` |
| Client ID | `foundly-frontend` |
| Client secret | Brak — klient publiczny |
| Scope | `openid profile email` |
| PKCE | `S256` |

Chronione żądania mają nagłówek `Authorization: Bearer <access_token>`. Backend sprawdza podpis, wystawcę, czas, audience `foundly-backend`, claim `typ=Bearer` i klienta `azp=foundly-frontend`. Nie przyjmuje ID tokenów. Mechanizm opiera się na [Spring Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html). Lokalne sprawdzanie JWT oznacza, że już wydany token może działać do upływu ważności, także po wylogowaniu.

Zmienne konfiguracji backendu: `DATABASE_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `KEYCLOAK_ISSUER`, `KEYCLOAK_JWKS`, `CORS_ORIGINS` (lista adresów oddzielona przecinkami). W kontenerze issuer pozostaje publicznym adresem realmu, a JWKS wskazuje wewnętrzny adres usługi Keycloak.

## Endpointy

| Metoda i ścieżka | Dostęp | Funkcja |
| --- | --- | --- |
| `GET /api/categories` | Publiczny | Aktywne kategorie |
| `GET /api/reports` | Publiczny | Lista, filtry, stronicowanie |
| `GET /api/reports/{id}` | Publiczny | Szczegóły z punktem lokalizacji do wyświetlenia na mapie |
| `POST /api/reports` | Użytkownik | Dodanie zgłoszenia; 201 i nagłówek `Location` |
| `PUT /api/me` | Użytkownik | Utworzenie lub synchronizacja własnego profilu z tokenu |
| `GET /api/me` | Użytkownik | Odczyt własnego profilu; 404, jeśli jeszcze nie istnieje |
| `GET /api/reports/mine` | Użytkownik | Lista własnych zgłoszeń z tymi samymi filtrami |
| `GET /api/reports/{id}/matches` | Autor | Aktualne dopasowania własnego zgłoszenia, od najwyższej oceny |
| `PUT /api/reports/{id}` | Autor | Edycja aktywnego zgłoszenia |
| `PATCH /api/reports/{id}/status` | Autor | Zakończenie sprawy |
| `DELETE /api/reports/{id}?version=0` | Autor | Fizyczne usunięcie; 204 |

Typ zgłoszenia (`lost`, `found`) i autor są niezmienne po utworzeniu. Dla zakończonego zgłoszenia dostępny jest odczyt i usunięcie. Nie ma automatycznego dostępu administratora do cudzych zgłoszeń.

### Profil po zalogowaniu

`PUT /api/me` nie wymaga body. Backend odczytuje `iss`, `sub`, `preferred_username`, `email`, `email_verified` i `iat` ze zweryfikowanego access tokenu. Zwraca **201** z nagłówkiem `Location: /api/me` przy utworzeniu oraz **200** dla istniejącego profilu. `GET /api/me` tylko odczytuje rekord i zwraca **404**, jeśli nie został utworzony. Body PUT nie służy do edycji danych; przesłane w nim pola nie zastępują claimów tokenu. Nazwę i adres zmienia się w Keycloak.

Przykładowa odpowiedź obu operacji:

```json
{
  "id": "2c1313de-893a-42a2-87f6-41ee004d7c63",
  "displayName": "demo",
  "email": "demo@example.test",
  "emailVerified": true,
  "createdAt": "2026-11-03T12:00:00Z",
  "updatedAt": "2026-11-03T12:00:00Z"
}
```

E-mail jest opcjonalny dla starszych kont i musi mieć poprawny format oraz najwyżej 254 znaki. Brak adresu oznacza `email: null` i `emailVerified: false`. E-mail nie jest kluczem konta i nie ma ograniczenia unikalności. Publiczne zgłoszenia nie zawierają adresów ich autorów.

Zapis profilu odbywa się przez JPA. Blokada rekordu serializuje równoczesne synchronizacje, a konflikt pierwszego utworzenia jest ponawiany w nowej transakcji po rollbacku. Profil jest identyfikowany zawsze przez `(iss, sub)`. Dane ze starszego tokenu nie nadpisują danych przyjętych z nowszego; przy identycznym `iat` (dokładność jednej sekundy) zachowujemy pierwszy zaakceptowany stan. Kolejne wydanie tokenu w następnej sekundzie pozwala zsynchronizować zmianę.

Migracja V2 dodaje pola bez usuwania kont i zgłoszeń; istniejące rekordy dostają `updatedAt = createdAt`, a e-mail uzupełni następna synchronizacja. Przyszłe powiadomienia powinny korzystać wyłącznie z niepustego, zweryfikowanego adresu. Wysyłka i preferencje powiadomień nie są częścią tego wdrożenia. Zmiany w Keycloak wykonane podczas nieobecności użytkownika docierają do lokalnego profilu przy kolejnej synchronizacji, bez natychmiastowej propagacji zdarzeń.

### Dodanie i edycja

Body `POST /api/reports`:

```json
{
  "title": "Klucze z niebieskim brelokiem",
  "type": "found",
  "categoryCode": "KEYS",
  "description": "Znaleziono pęk trzech kluczy przy ławce w Plantach.",
  "color": "Niebieski",
  "eventDate": "2026-06-18",
  "location": {
    "label": "Kraków, Stare Miasto",
    "longitude": 19.937,
    "latitude": 50.061
  }
}
```

Data zdarzenia nie może być przyszła według strefy `Europe/Warsaw`. Współrzędne i etykieta są wymagane. Długość to `longitude`, szerokość to `latitude`. Kolor można pominąć lub przekazać jako `null`. Kategorie: `ELECTRONICS`, `KEYS`, `WALLETS_DOCUMENTS`, `BAGS_BACKPACKS`, `JEWELLERY`, `OTHER`.

Body `PUT /api/reports/{id}` zawiera oczekiwaną wersję oraz pełny zestaw edytowalnych pól w `content` (takich jak w POST, z pominięciem `type`):

```json
{
  "version": 0,
  "content": {
    "title": "Klucze z niebieskim brelokiem",
    "categoryCode": "KEYS",
    "description": "Znaleziono pęk trzech kluczy przy ławce w Plantach.",
    "color": "Niebieski",
    "eventDate": "2026-06-18",
    "location": {"label": "Kraków, Stare Miasto", "longitude": 19.937, "latitude": 50.061}
  }
}
```

Body `PATCH /api/reports/{id}/status`: `{"version": 1, "status": "RESOLVED"}`. Odpowiedź zawiera nową `version`. Powtórne zakończenie jest idempotentne i nie zmienia `resolvedAt`, również przy ponowieniu z wersją sprzed zakończenia. Dla edycji i usunięcia nieaktualna wersja daje 409.

Nieznane pola żądań są odrzucane, np. próba przesłania `authorId` albo `recovered`. Backend wyznacza autora z `(iss, sub)` tokenu. Lokalny profil jest tworzony atomowo; zmiana nazwy Keycloak nie zmienia jego identyfikatora ani autorstwa.

### Listy i wyszukiwanie

Parametry `GET /api/reports` i `/api/reports/mine`:

| Parametr | Znaczenie |
| --- | --- |
| `page`, `size` | Strona od 0, rozmiar 1–100; domyślnie 0 i 20 |
| `type` | `lost` lub `found` |
| `categoryCode` | Kod kategorii |
| `status` | `ACTIVE` (domyślny), `RESOLVED` lub `ALL` |
| `dateFrom`, `dateTo` | Granice daty zdarzenia, włącznie, `YYYY-MM-DD` |
| `q` | Fragment tytułu lub opisu, bez rozróżniania wielkości liter; `%` i `_` traktowane dosłownie |
| `location` | Fragment nazwy miejsca zdarzenia, np. `Mokotów`, bez rozróżniania wielkości liter |
| `longitude`, `latitude`, `radiusMeters` | Komplet parametrów wyszukiwania w promieniu 1–100000 m |

Bez promienia lista jest sortowana od najnowszej publikacji, z deterministycznym rozstrzyganiem remisów po UUID. Z promieniem jest sortowana od najbliższej lokalizacji. Wyszukiwanie używa `ST_DWithin` na `geography(Point,4326)` oraz indeksu GiST dla aktywnych zgłoszeń. Filtry są budowane przez JPA `Specification`, a pobieranie stron i liczenie wyników obsługuje `ReportRepository` z `JpaSpecificationExecutor` oraz `Pageable`. Sortowanie po odległości jest wyrażone przez Criteria API, a po dacie publikacji przez `Sort`. Relacje autora i kategorii są pobierane z `@EntityGraph`, aby uniknąć dodatkowego zapytania dla każdego zgłoszenia.

Strona ma pola `content`, `page`, `size`, `totalElements`, `totalPages`. Element zawiera wszystkie pola obecnego frontendu (`id`, `title`, `type`, `category`, `location`, `date`, `color`, `description`, `owner`, `recovered`) oraz `categoryCode`, `status`, `version` i znaczniki czasu. W szczegółach pole `locationPoint` zawiera publiczne współrzędne (`latitude`, `longitude`) miejsca zdarzenia do wyświetlenia na mapie; na listach jest `null`. W szczegółach autora pole `editableLocation` zawiera etykietę i współrzędne, w innych odpowiedziach jest `null`.

### Odczyt automatycznych dopasowań

`GET /api/reports/{id}/matches?page=0&size=20` wymaga access tokenu autora
wskazanego ogłoszenia. Działa po stronie LOST i FOUND; pole `report` opisuje
drugie ogłoszenie z pary. Parametry: `page >= 0`, `size` od 1 do 100, domyślnie
0 i 20. Odpowiedź ma pola `content`, `page`, `size`, `totalElements`, `totalPages`.
Wyniki są sortowane malejąco po zapisanym `finalScore`, a remisy po UUID pary.

Każdy element zawiera `id` pary, `status`, `finalScore`, `scores` (semantyka,
lokalizacja, data, kategoria, pokrycie dostępnych danych), `approximateDistanceMeters`,
`daysAfterLoss`, czasy utworzenia i aktualizacji oraz skrót drugiego ogłoszenia.
Oceny są zaokrąglone do dwóch miejsc, odległość do pełnych metrów
(np. 90,67 metra daje 91 m). Ocena nie jest prawdopodobieństwem własności.
Odpowiedź nie zawiera autora, kontaktu, opisu, dokładnych współrzędnych ani danych
wektora. Dotychczasowy publiczny endpoint szczegółów nadal udostępnia punkt mapy;
zmiana jego polityki prywatności jest osobnym zadaniem.

Odczyt uwzględnia `PROPOSED` i `CONFIRMED`, wyłącznie dla aktywnych ogłoszeń
o aktualnych wersjach, zgodnych fingerprintach embeddingów i bieżącej wersji
rankingu oraz progu semantyki. Ukrywa nieaktualne pary również przed ich
wygaszeniem przez workera. Nie wywołuje AI i nie zapisuje danych. Sprawdzenie
właściciela, strona i licznik działają w transakcji `REPEATABLE_READ`.

Test ręczny: przebuduj backend, utwórz podobne LOST i FOUND z dwóch różnych
kont, poczekaj na `COMPLETED` zadania procesu `v2`, następnie wykonaj żądanie
z tokenem właściciela wskazanego ogłoszenia:

```sh
docker compose --profile app up -d --build backend

curl --fail-with-body \
  'http://localhost:8080/api/reports/<ID_TWOJEGO_OGLOSZENIA>/matches?page=0&size=20' \
  -H 'Authorization: Bearer <ACCESS_TOKEN_WLASCICIELA>'
```

Pusta lista z HTTP 200 oznacza brak aktualnych dopasowań; analiza może jeszcze
trwać. Brak poprawnego tokenu daje 401, cudze ogłoszenie 403, brak ogłoszenia 404,
a niepoprawna paginacja lub UUID 400. Endpoint nie wymaga nowej migracji.

Kolejne etapy to decyzje użytkowników dotyczące par, powiadomienia i widok we
frontendzie. Sam odczyt nie potwierdza odzyskania przedmiotu.

### Błędy

API stosuje HTTP 400 (walidacja, niepoprawne dane), 401 (brak poprawnego tokenu), 403 (cudze zgłoszenie), 404 (brak zgłoszenia) i 409 (konflikt wersji lub niedozwolony stan). Błędy mają format `application/problem+json` z polami `type`, `title`, `status`, `detail`; przy błędach walidacji DTO szczegóły zależą od Spring MVC.

## Testy

```bash
cd foundly-backend
./mvnw test
./mvnw package
```

Testy integracyjne uruchamiają tymczasowy PostgreSQL/PostGIS/pgvector przez Testcontainers, budując obraz z `docker/postgres/Dockerfile`, wykonują migracje i sprawdzają API przez MockMvc. Obejmują CRUD, dostęp, walidację, geografię, OpenAPI i współbieżne tworzenie profili. Dekoder JWT jest zastąpiony w testach API; osobne testy rzeczywistego dekodera z lokalnym JWKS sprawdzają podpisy, czas, wystawcę, audience i rodzaj tokenu. Testy integracyjne są pomijane przy braku Dockera — sprawdź liczbę `Skipped` w wyniku. Do pełnej weryfikacji potrzebny jest działający Docker.
