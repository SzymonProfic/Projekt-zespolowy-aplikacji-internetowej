# Budowanie i uruchamianie Foundly
## Wymagania

| Sposób uruchomienia | Wymagania |
| --- | --- |
| Lokalnie | JDK 21, ustawione `JAVA_HOME` |
| W Dockerze | Zainstalowany i uruchomiony Docker |

Maven jest pobierany przez dołączony Maven Wrapper. Przy pierwszym budowaniu potrzebny jest dostęp do internetu do pobrania narzędzi, zależności oraz — dla Dockera — obrazów bazowych. Wariant dockerowy nie wymaga lokalnej instalacji Javy ani Mavena.

## Pobranie projektu

```bash
git clone https://github.com/SzymonProfic/Projekt-zespolowy-aplikacji-internetowej.git
cd Projekt-zespolowy-aplikacji-internetowej
```

Jeśli masz już repozytorium, otwórz terminal w jego głównym katalogu.

## Backend lokalnie

Polecenia w tej sekcji wykonuj w katalogu `foundly-backend`:

```bash
cd foundly-backend
java -version
./mvnw spring-boot:run
```

`java -version` powinno wskazywać Javę 21. Aplikacja uruchomi się na porcie `8080`. Zatrzymasz ją skrótem `Ctrl+C` w terminalu, w którym działa.

W Windows zamiast `./mvnw` używaj `.\mvnw.cmd`, np.:

```powershell
.\mvnw.cmd spring-boot:run
```

### Testy

```bash
./mvnw test
```

Wyniki testów znajdziesz w `target/surefire-reports/`.

### Budowanie i uruchamianie pliku JAR

```bash
./mvnw clean package
java -jar target/foundly-backend-0.0.1-SNAPSHOT.jar
```

`clean package` usuwa poprzedni wynik budowania, kompiluje aplikację, uruchamia testy i tworzy wykonywalny JAR. Nazwa pliku wynika z `artifactId` i `version` w `pom.xml`; po zmianie wersji należy dostosować polecenie.

## Backend w Dockerze

Wszystkie polecenia budowania obrazu wykonuj z głównego katalogu repozytorium. Jeśli jesteś w `foundly-backend`, najpierw wykonaj `cd ..`.

### Budowanie obrazu

```bash
docker build -t foundly-backend ./foundly-backend
```

Dockerfile ma dwa etapy:

1. Obraz z JDK 21 pobiera zależności przez Maven Wrapper, kompiluje kod, uruchamia testy i tworzy JAR.
2. Obraz z JRE 21 otrzymuje gotowy JAR i uruchamia go jako użytkownik `foundly`, bez uprawnień roota.

Docker przechowuje warstwy w pamięci podręcznej. Zmiana samych źródeł pozwala wykorzystać wcześniej pobrane zależności. Plik `../foundly-backend/.dockerignore` wyklucza z kontekstu budowania m.in. lokalny katalog `target/` i pliki `.env`.

### Uruchamianie w terminalu

```bash
docker run --rm --name foundly-backend -p 8080:8080 foundly-backend
```

Pierwszy port w `8080:8080` oznacza port komputera, drugi — port aplikacji w kontenerze. Logi pojawiają się w terminalu. `--rm` automatycznie usuwa kontener po jego zatrzymaniu; obraz pozostaje dostępny.

Zatrzymanie z drugiego terminala:

```bash
docker stop foundly-backend
```

### Uruchamianie w tle i logi

Zamiast powyższego `docker run` można użyć wariantu z `-d`:

```bash
docker run -d --rm --name foundly-backend -p 8080:8080 foundly-backend
docker ps
docker logs -f foundly-backend
```

`Ctrl+C` podczas śledzenia logów kończy ich podgląd. Kontener nadal działa; zatrzymasz go przez `docker stop foundly-backend`.

### Ponowne budowanie po zmianie kodu

Kontener uruchamia kod zapisany w obrazie. Aby zastosować zmiany w źródłach, zbuduj obraz ponownie, zatrzymaj poprzedni kontener i uruchom nowy:

```bash
docker build -t foundly-backend ./foundly-backend
docker stop foundly-backend
docker run -d --rm --name foundly-backend -p 8080:8080 foundly-backend
```

Polecenie `docker stop` jest potrzebne, jeśli poprzedni kontener nadal działa.

## Frontend w Dockerze (szablon)

W `frontend/` przygotowano szablon dla React + TypeScript + Vite z menedżerem npm. **Na tym etapie nie można jeszcze zbudować obrazu frontendu**, ponieważ repozytorium nie zawiera kodu aplikacji ani plików npm.

### Wymagania przed pierwszym budowaniem

W katalogu `frontend/` należy dodać:

- `package.json` z zależnościami React, TypeScript i Vite oraz skryptem `build`, np. `"build": "tsc -b && vite build"` dla projektu z konfiguracją TypeScript obsługującą `tsc -b`;
- `package-lock.json` wygenerowany przez `npm install` i zapisany w repozytorium — Dockerfile używa `npm ci`, które wymaga zgodnego lockfile;
- `index.html`, kod aplikacji w `src/` oraz konfigurację TypeScript i Vite.

Build powinien zapisywać gotową aplikację w `dist/`, zgodnie z domyślnym ustawieniem Vite. Po zmianie `build.outDir` trzeba dostosować ścieżkę `COPY --from=build` w Dockerfile. Przy implementacji zachowaj przygotowane pliki `Dockerfile`, `nginx.conf` i `.dockerignore`.

### Jak działa obraz

1. Etap `build` korzysta z Node.js 24, instaluje zależności przez `npm ci` i wykonuje `npm run build`. Zależności deweloperskie są potrzebne do kompilacji TypeScript i uruchomienia Vite.
2. Końcowy obraz `nginx/nginx-unprivileged:stable-alpine` otrzymuje tylko wynik z `dist/` i konfigurację serwera. Serwuje aplikację jako użytkownik bez uprawnień roota na porcie `8080`.

`nginx.conf` przekierowuje trasy aplikacji na `index.html`, aby odświeżenie strony React Routera, np. `/ogloszenia/123`, działało poprawnie. Brakujące pliki w `/assets/` zwracają `404`.

Plik `frontend/.dockerignore` wyklucza m.in. lokalne `node_modules/`, `dist/` i pliki `.env`. Własna konfiguracja przez zmienne `VITE_*` będzie wymagała dodania odpowiednich `ARG`/`ENV` w etapie budowania. Vite zapisuje ich wartości w publicznych plikach aplikacji, więc nie należy umieszczać w nich sekretów. Samo `docker run -e VITE_...` nie zmieni już zbudowanej aplikacji.

### Budowanie i uruchamianie po dodaniu aplikacji

Z głównego katalogu repozytorium:

```bash
docker build -t foundly-frontend ./frontend
docker run --rm --name foundly-frontend -p 3000:8080 foundly-frontend
```

Frontend będzie dostępny pod adresem http://localhost:3000. Port `3000` komputera pozwala uruchomić go równolegle z backendem dostępnym na porcie `8080`. Do budowania w Dockerze nie trzeba instalować lokalnie Node.js ani npm.

Zatrzymanie z drugiego terminala:

```bash
docker stop foundly-frontend
```

Po zmianie kodu należy ponownie zbudować obraz i uruchomić nowy kontener. Do codziennej pracy z automatycznym odświeżaniem używaj serwera Vite (`npm run dev`) po dodaniu aplikacji.

Szablon serwuje frontend. Połączenie z API (adres backendu, CORS lub proxy Nginx) trzeba skonfigurować przy integracji. `docker-compose.yml` zawiera na razie tylko bazę danych, więc uruchamianie całego środowiska przez Compose nie jest jeszcze dostępne.

Źródła: [budowanie i katalog `dist` w Vite](https://vite.dev/guide/static-deploy), [obraz Nginx bez uprawnień roota](https://github.com/nginx/docker-nginx-unprivileged).

## Sprawdzenie działania backendu

Poczekaj, aż w logach pojawi się komunikat `Started FoundlyBackendApplication`, a następnie otwórz w przeglądarce:

http://localhost:8080/api/v1/test

Możesz też użyć terminala:

```bash
curl http://localhost:8080/api/v1/test
```

Oczekiwana odpowiedź: `test`. Adres działa zarówno dla aplikacji uruchomionej lokalnie, jak i dla kontenera z mapowaniem `8080:8080`.

## Baza danych

Domyślna konfiguracja w `../foundly-backend/src/main/resources/application.properties` używa H2 w pamięci. Nie wymaga osobnego kontenera bazy ani zmiennych środowiskowych. Dane są usuwane po zatrzymaniu aplikacji, również podczas wymiany kontenera.

Przy uruchomieniu lokalnym konsola H2 jest dostępna pod adresem http://localhost:8080/h2-console:

| Pole | Wartość |
| --- | --- |
| JDBC URL | `jdbc:h2:mem:foundly` |
| User Name | `sa` |
| Password | puste |

Domyślne ustawienia H2 ograniczają dostęp do konsoli z innych hostów, co może blokować korzystanie z niej przez opublikowany port kontenera. Do sprawdzenia działania kontenera używaj endpointu testowego.

### PostgreSQL w Docker Compose (szablon)

`docker-compose.yml` zawiera usługę `db` z obrazem `postgres:18-alpine`, trwałym wolumenem `postgres_data` i healthcheckiem `pg_isready`. Baza jest udostępniana wyłącznie na `127.0.0.1` komputera. Backend nadal domyślnie korzysta z H2; samo uruchomienie Compose nie przełącza jego konfiguracji.

W głównym katalogu repozytorium skopiuj konfigurację:

```bash
cp .env.example .env
```

W PowerShell użyj `Copy-Item .env.example .env`. Ustaw własne `POSTGRES_PASSWORD` w `.env`. Plik `.env` jest pomijany przez Git; `.env.example` pozostaje wspólnym szablonem. Compose automatycznie odczytuje `.env`. Bez ustawionego, niepustego hasła zgłosi błąd konfiguracji.

Sprawdź konfigurację i uruchom bazę:

```bash
docker compose config --quiet
docker compose up -d --wait db
docker compose ps
docker compose logs -f db
```

`--wait` czeka na gotowość bazy potwierdzoną healthcheckiem. `Ctrl+C` kończy podgląd logów, pozostawiając bazę uruchomioną. Wymagany jest Docker Compose v2 z obsługą `--wait`.

Domyślne parametry połączenia z komputera (np. z IntelliJ lub DBeaver):

| Parametr | Wartość |
| --- | --- |
| Host | `127.0.0.1` |
| Port | `5432` lub wartość `POSTGRES_PORT` z `.env` |
| Baza | `foundly` lub wartość `POSTGRES_DB` |
| Użytkownik | `foundly` lub wartość `POSTGRES_USER` |
| Hasło | wartość `POSTGRES_PASSWORD` z `.env` |

Zapytanie kontrolne bez instalowania lokalnego klienta PostgreSQL:

```bash
docker compose exec db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT 1;"'
```

Zatrzymanie i usunięcie kontenera z zachowaniem danych:

```bash
docker compose down
```

Wolumen zachowuje dane po ponownym utworzeniu kontenera. **`docker compose down -v` usuwa także wolumen i całą zawartość bazy.** Zmienne `POSTGRES_DB`, `POSTGRES_USER` i `POSTGRES_PASSWORD` inicjalizują bazę tylko przy pustym wolumenie; ich zmiana w `.env` nie aktualizuje istniejącej bazy ani hasła użytkownika.

Przy przyszłej integracji backend uruchamiany na komputerze powinien otrzymać adres `jdbc:postgresql://127.0.0.1:5432/foundly` (z uwzględnieniem ustawień `.env`), a backend w tej samej sieci Compose — `jdbc:postgresql://db:5432/foundly`. Służą do tego zmienne Spring Boot `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` i `SPRING_DATASOURCE_PASSWORD`. Spring Boot uruchamiany lokalnie nie odczytuje automatycznie pliku `.env` Compose; zmienne trzeba przekazać przez terminal lub konfigurację uruchomienia IDE.

Przed podłączeniem backendu należy też zastąpić domyślne `spring.jpa.hibernate.ddl-auto=create-drop` ustawieniem odpowiadającym przyjętemu zarządzaniu schematem, aby backend nie usuwał tabel przy zatrzymaniu. Przy dodawaniu usługi backendu do Compose można użyć `depends_on: db: condition: service_healthy` w postaci zagnieżdżonego YAML, aby czekała na gotowość bazy.

Źródła: [oficjalny obraz PostgreSQL i inicjalizacja danych](https://hub.docker.com/_/postgres), [healthcheck i kolejność startu w Compose](https://docs.docker.com/compose/how-tos/startup-order/).

## Typowe problemy

| Problem | Rozwiązanie |
| --- | --- |
| Zajęty port `8080` | Zatrzymaj drugą instancję aplikacji albo użyj w Dockerze `-p 8081:8080`. Wtedy API będzie pod `http://localhost:8081/api/v1/test`. |
| Nazwa kontenera jest zajęta | Sprawdź `docker ps -a`. Zatrzymaj poprzedni kontener lub użyj innej wartości `--name`. Kontener uruchomiony z `--rm` znika po zatrzymaniu. |
| Brak połączenia z Dockerem | Uruchom Docker Desktop lub lokalny silnik Docker i sprawdź `docker info`. |
| `Permission denied` przy `./mvnw` | W katalogu backendu wykonaj `chmod +x mvnw` i ponów polecenie. |
| Błędna wersja Javy | Sprawdź `java -version` oraz ustawienie `JAVA_HOME`; budowanie lokalne wymaga JDK 21. |
| Nie można pobrać zależności lub obrazów | Sprawdź połączenie z internetem i konfigurację proxy, jeśli go używasz. |
| Endpoint nie odpowiada od razu | Poczekaj na zakończenie startu aplikacji; w Dockerze sprawdź `docker logs foundly-backend`. |
