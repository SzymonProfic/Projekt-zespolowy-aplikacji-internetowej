# Dane developerskie do oceny dopasowań

Aktualny zestaw **v2 zawiera 174 fikcyjne ogłoszenia**: 55 LOST i 119 FOUND.
Do poprzednich 34 dopisano 140 nowych, zachowując stare ID i treści. Wszystkie
ogłoszenia należą do istniejących kont `demo` i `test`; hasła i konfiguracja
Keycloak pozostają bez zmian. To narzędzie developerskie, nie migracja Flyway.

## Podział danych

| Podzbiór | Ogłoszenia | LOST | FOUND | Przeznaczenie |
|---|---:|---:|---:|---|
| `tuning` | 105 | 33 | 72 | Dobieranie progu i wag |
| `validation` | 69 | 22 | 47 | Sprawdzenie po ustaleniu konfiguracji |

Wszystkie warianty jednego fizycznego przedmiotu i przypisane mu trudne negatywy
pozostają w tym samym podzbiorze (`family`). Walidator odrzuca rozdzielenie tej
samej rodziny lub tożsamości `item` między podzbiory. Nie dobieraj konfiguracji
na wynikach validation — po obejrzeniu i wykorzystaniu tych wyników do zmian
potrzebny będzie kolejny niezależny zestaw.

Skrypt domyślnie ocenia **tylko tuning**. Aplikacja nie zna tego podziału i widzi
całą bazę; podział jest metadanymi narzędzia testowego, nie filtrem biznesowym.
W raportach obie strony pary należą do wybranego podzbioru. Metryki nie obejmują
sugestii między podzbiorami, więc nie są pełną oceną wszystkich wyników API.
Globalny limit zapisu par nadal wpływa na to, jakie pary są dostępne do oceny.

## Scenariusze

Nowe dane obejmują 40 rodzin przedmiotów. Dla każdej przygotowano LOST,
poprawne FOUND opisane innymi słowami oraz FOUND innego przedmiotu podobnego
rodzaju. Są m.in. klucze, tablety, laptopy, czytniki, aparaty, biżuteria, torby,
okulary, ubrania, zabawki, sprzęt sportowy, książki i akcesoria.

Dodatkowo:

- 5 LOST i 5 FOUND bez prawidłowego odpowiednika w zestawie;
- 6 nowych niejednoznacznych opisów, których nie należy oceniać jako pewne negatywy;
- 4 dodatkowe poprawne warianty: błędna kategoria, duża odległość i opóźnienie,
  literówki, krótki opis;
- wcześniejsze scenariusze synonimów, różnych cech, tego samego autora
  i zakończonego zgłoszenia.

W całej bazie jest 6545 możliwych par LOST/FOUND: 57 `MATCH`, 6370 `NO_MATCH`,
8 `UNCERTAIN` i 110 `EXCLUDED`. Etykiety wynikają z zadeklarowanego syntetycznego
inwentarza, a nie z ocen modelu. To dane do przejrzenia przez człowieka, nie
potwierdzenie skuteczności na rzeczywistych zgłoszeniach. Opisy generowane w ten
sposób nie zastępują przykładów pisanych niezależnie przez prawdziwych użytkowników.

`item` identyfikuje fikcyjny fizyczny przedmiot. `ambiguous_for` wskazuje przedmioty,
których nie da się wykluczyć na podstawie ogólnego opisu. `family` grupuje warianty
i podobne, ale inne przedmioty. Te pola oraz `key`, `scenario`, `split` nigdy nie
trafiają do bazy ani embedding-service. Warianty jednego przedmiotu reprezentują
alternatywne sytuacje testowe, nie automatyczne potwierdzenie własności.

Wszystkie ogłoszenia mają lokalizację i datę, bo obecny schemat wymaga tych pól.
Zestaw nie sprawdza braku lokalizacji lub daty. Daty są stałe, a współrzędne
wskazują miejsca publiczne w kilku miastach. Nie ma danych osobowych w opisach.

## Dopisanie danych bez czyszczenia bazy

Uruchamiaj z katalogu głównego projektu przy działającym Docker Compose,
backendzie oraz embedding-service. Zatrzymaj osobny backend z IDE, jeśli działa.

```sh
python3 scripts/dev-matching/manage.py validate
python3 scripts/dev-matching/manage.py append --apply
```

`append` dodaje tylko brakujące ID z fixtures.json i zleca dla nich zadania `v2`.
Nie nadpisuje istniejących ogłoszeń, embeddingów ani historii zadań. Konflikt
między treścią istniejącego ogłoszenia a fixture przerywa całą transakcję.
Ponowne wywołanie na niezmienionych danych nie dodaje duplikatów. Ręcznie dodane
ogłoszenia o innych ID pozostają w bazie, ale wtedy evaluator odmówi oceny,
żeby nie raportować metryk dla niekontrolowanego zestawu.

W obu trybach zapisu skrypt wymaga lokalnego socketa Dockera, bazy `foundly`
i dokładnie jednego lokalnego profilu dla każdego konta `demo` i `test`,
z issuerem `http://localhost:8081/realms/foundly`. Zatrzymuje backend, wykonuje
pełny `pg_dump -Fc` do prywatnego katalogu tymczasowego, sprawdza czytelność
archiwum, zapisuje dane w jednej transakcji i uruchamia backend również po błędzie.
Ścieżkę kopii wypisuje na ekranie. Kopię potrzebną dłużej przenieś do prywatnego
katalogu poza repozytorium — system może posprzątać pliki tymczasowe.

Aby świadomie zastąpić **wszystkie** ogłoszenia i ich embeddingi, zadania oraz
pary całym zestawem v2, użyj osobnej komendy:

```sh
python3 scripts/dev-matching/manage.py seed --apply
```

`seed` zachowuje konta, kategorie i historię Flyway. W tym trybie aktualny zestaw
v2 zastępuje również ogłoszenia dodane ręcznie. To nie jest potrzebne przy append.

Seed przez SQL omija kontroler i walidację HTTP, ale uruchamia rzeczywisty worker,
generowanie embeddingów i zapis rankingu. Po zakończeniu oczekujemy 174 ogłoszeń,
173 embeddingów i 173 zadań `COMPLETED`. Zakończone FOUND nie ma embeddingu.
Progi i wagi nie są zmieniane przez ładowanie danych.

## Ocena rzeczywistych wyników

Po zakończeniu zadań:

```sh
python3 scripts/dev-matching/manage.py evaluate
```

Domyślne wyniki trafiają do `/tmp/foundly-matching-evaluation-v2/tuning/summary.md`
i `pairs.csv`. Katalog można zmienić przez `--output /ścieżka/do/wyników`.
Walidację uruchom jawnie **po ustaleniu konfiguracji**:

```sh
python3 scripts/dev-matching/manage.py evaluate --split validation
```

Ten raport trafia do `/tmp/foundly-matching-evaluation-v2/validation/`.
Evaluator nie zmienia progów ani nie generuje wektorów. Sprawdza ID zestawu,
ukończenie wszystkich zadań i brak zapisanych par wykluczonych w ocenianym
podzbiorze. Raport zawiera hash fixtures.json oraz metadane modelu i rankingu.

CSV zawiera surowy cosine również dla niezapisanych par. Brak finalScore oznacza
brak zapisanej pary, nie wynik 0. Pozwala to zauważyć poprawne pary odfiltrowane
przed zapisem. Podsumowanie pokazuje kilka progów finalScore i pozycję pierwszej
poprawnej pary dla każdego LOST. Dla LOST bez odpowiednika poprawne jest to,
że nie ma żadnej poprawnej pary; błędne propozycje nadal obniżają precision.
UNCERTAIN i EXCLUDED nie są liczone jako negatywne przykłady.

Precision to udział poprawnych par w sugestiach o znanej etykiecie, recall to
udział odnalezionych poprawnych par we wszystkich oczekiwanych w podzbiorze.
Oceny zaokrąglone w API nie służą do strojenia — CSV zawiera pełną precyzję.
Remisy raportu rozstrzyga klucz scenariusza, a API UUID pary.

Historyczny [baseline-v1.md](baseline-v1.md) dotyczy wyłącznie dawnych 34 ogłoszeń.
Oryginalny plik zachowano jako fixtures-v1.json. Nie porównuj bezpośrednio metryk
v1 i v2: mają inną liczbę oraz trudność przykładów.

Pierwszy raport dla części do strojenia v2 zapisano w
[baseline-v2-tuning.md](baseline-v2-tuning.md). Powstał przy niezmienionych
progach i wagach. Część validation nie została oceniona.

[Analiza v2 i proponowane zmiany](analysis-v2-tuning.md) porównuje progi
semantyczne i kilka zestawów wag wyłącznie na części tuning.

## Test API

Pełna [lista ID i scenariuszy jest w catalog.md](catalog.md). Konta są te same:
`demo` jest autorem LOST, `test` autorem większości FOUND. Token weź z normalnego
logowania. ID wcześniejszych ogłoszeń pozostały bez zmian.

Przykład dla dotychczasowych zgubionych kluczy, z tokenem demo:

```sh
curl --fail-with-body \
  'http://localhost:8080/api/reports/e7ed5e43-cf9a-544c-a512-9eb3848f0a3f/matches?page=0&size=20' \
  -H 'Authorization: Bearer ACCESS_TOKEN_DEMO'
```

Pamiętaj o kolejnych stronach. Wynik API może zawierać więcej par niż limit
jednego zadania, bo inne ogłoszenia również zapisują pary do tego samego LOST.
Ogłoszenie zakończone zwraca pustą listę. FOUND tego samego autora nie powinno
pojawić się w dopasowaniach jego LOST.

## Przywrócenie wcześniejszych danych

Przywrócenie zastępuje aktualną bazę aplikacji kopią wypisaną przez skrypt.
Nie dotyczy bazy Keycloak. Wykonuj je świadomie po zatrzymaniu backendu:

```sh
docker compose stop backend
docker compose exec -T db sh -c 'exec pg_restore --clean --if-exists --exit-on-error --single-transaction -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < /SCIEZKA/DO/foundly.dump
docker compose start backend
```
