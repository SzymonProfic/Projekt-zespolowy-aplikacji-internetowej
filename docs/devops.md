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

## Sprawdzenie działania

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

Sterownik PostgreSQL jest dodany do projektu, ale środowisko z PostgreSQL nie jest jeszcze skonfigurowane.

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
