# Foundly — REST API

Backend Java 21 / Spring Boot 4.1, PostgreSQL 18 + PostGIS, migracje Flyway i uwierzytelnianie Keycloak. Model domeny opisuje [model.md](model.md). API nie korzysta już z H2.

## Uruchomienie lokalne

Z głównego katalogu repozytorium:

```bash
cp .env.example .env
# Ustaw własne hasła w .env przed pierwszym uruchomieniem.
docker compose up -d db keycloak-init
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

Backend: `http://localhost:8080`. Migracje tworzą tabele, ograniczenia, słownik sześciu kategorii i indeksy PostGIS. Domyślnie baza działa na porcie 5432; po zmianie `POSTGRES_PORT` ustaw także `DATABASE_URL=jdbc:postgresql://localhost:<port>/foundly` (z właściwą nazwą bazy).

Alternatywnie uruchom backend w kontenerze:

```bash
docker compose --profile app up -d --build
```

Compose wykorzystuje osobne bazy i wolumeny dla Foundly i Keycloak. Nowy `postgis_data` nie montuje ani nie usuwa starego `postgres_data` ze wcześniejszego szablonu. Przeniesienie dawnych danych wymaga jawnego eksportu/importu. Obraz PostGIS działa jako `linux/amd64`, na Apple Silicon przez emulację, zgodnie z [obsługiwanymi architekturami obrazu](https://github.com/postgis/docker-postgis). Konfiguracja Keycloak `start-dev` i lokalny HTTP służą pracy deweloperskiej.

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
| `GET /api/reports/{id}` | Publiczny | Szczegóły; współrzędne wyłącznie dla autora |
| `POST /api/reports` | Użytkownik | Dodanie zgłoszenia; 201 i nagłówek `Location` |
| `PUT /api/me` | Użytkownik | Utworzenie lub synchronizacja własnego profilu z tokenu |
| `GET /api/me` | Użytkownik | Odczyt własnego profilu; 404, jeśli jeszcze nie istnieje |
| `GET /api/reports/mine` | Użytkownik | Lista własnych zgłoszeń z tymi samymi filtrami |
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
| `longitude`, `latitude`, `radiusMeters` | Komplet parametrów wyszukiwania w promieniu 1–100000 m |

Bez promienia lista jest sortowana od najnowszej publikacji, z deterministycznym rozstrzyganiem remisów po UUID. Z promieniem jest sortowana od najbliższej lokalizacji. Wyszukiwanie używa `ST_DWithin` na `geography(Point,4326)` oraz indeksu GiST dla aktywnych zgłoszeń. Filtry są budowane przez JPA `Specification`, a pobieranie stron i liczenie wyników obsługuje `ReportRepository` z `JpaSpecificationExecutor` oraz `Pageable`. Sortowanie po odległości jest wyrażone przez Criteria API, a po dacie publikacji przez `Sort`. Relacje autora i kategorii są pobierane z `@EntityGraph`, aby uniknąć dodatkowego zapytania dla każdego zgłoszenia.

Strona ma pola `content`, `page`, `size`, `totalElements`, `totalPages`. Element zawiera wszystkie pola obecnego frontendu (`id`, `title`, `type`, `category`, `location`, `date`, `color`, `description`, `owner`, `recovered`) oraz `categoryCode`, `status`, `version` i znaczniki czasu. W szczegółach autora pole `editableLocation` zawiera etykietę i współrzędne, w innych odpowiedziach jest `null`.

### Zakres kolejnego sprintu

Automatyczne dopasowywanie zgłoszeń (matching) jest odłożone do kolejnego sprintu. Obecne API obejmuje CRUD zgłoszeń, zmianę statusu, „Moje zgłoszenia” oraz ręczne wyszukiwanie i filtrowanie listy. Nie udostępnia endpointu dopasowań ani punktacji podobieństwa.

### Błędy

API stosuje HTTP 400 (walidacja, niepoprawne dane), 401 (brak poprawnego tokenu), 403 (cudze zgłoszenie), 404 (brak zgłoszenia) i 409 (konflikt wersji lub niedozwolony stan). Błędy mają format `application/problem+json` z polami `type`, `title`, `status`, `detail`; przy błędach walidacji DTO szczegóły zależą od Spring MVC.

## Budowanie i testy

Automatyczne testy i ich konfiguracja są przewidziane na osobny sprint. W tym commicie pozostaje tylko pierwotny szkielet testu Spring Boot; nie jest jeszcze dostosowany do PostgreSQL/PostGIS i Keycloak.

Budowanie aplikacji w tym sprincie pomija kompilację i uruchamianie testów:

```bash
cd foundly-backend
./mvnw -Dmaven.test.skip=true package
```

Dockerfile używa tego samego ustawienia. Docelowe testy powinny obejmować CRUD, autoryzację, migracje, filtrowanie PostGIS oraz synchronizację profilu i e-maila; ich implementacja zostanie dodana w osobnym commicie.
