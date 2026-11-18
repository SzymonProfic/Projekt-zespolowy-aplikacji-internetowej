# Wyszukiwarka ogłoszeń — ustalenia i plan rozwoju

Stan ustaleń: 2026-11-13. Dokument zbiera rozmowę o wyszukiwaniu z „lupki”
na frontendzie. Opisuje obecne działanie, proponowany kierunek i decyzje,
które pozostają do podjęcia. Nie oznacza wdrożenia FTS ani wyszukiwania semantycznego.

## 1. Cel

Użytkownik wpisuje nazwę przedmiotu, jego cechy lub krótki opis i otrzymuje
ogłoszenia uporządkowane według trafności. Wyszukiwanie powinno obejmować tytuł
i opis oraz współpracować z filtrami typu, kategorii, daty i lokalizacji.

Lupka służy ręcznemu przeglądaniu ogłoszeń. Automatyczny matching LOST/FOUND
pozostaje oddzielnym procesem, działającym po publikacji lub edycji zgłoszenia.
Wyszukiwarka nie musi ograniczać wyników do przeciwnego typu ani wykluczać
własnych ogłoszeń użytkownika, jak robi to matching.

## 2. Co działa obecnie

Istniejący endpoint:

```http
GET /api/reports?q=klucze
```

Parametr q jest wyszukiwany jako fragment tekstu w tytule LUB opisie, bez
rozróżniania wielkości liter. Implementacja używa lower(...) i LIKE przez
JPA Criteria API. Całe q jest jednym wzorcem: `czarny portfel` nie dopasuje
automatycznie `czarny skórzany portfel`.

| Parametr | Obecne działanie |
|---|---|
| q | Fragment tytułu lub opisu; maksymalnie 200 znaków |
| type | lost lub found |
| categoryCode | Kod kategorii |
| status | ACTIVE, RESOLVED lub ALL; domyślnie ACTIVE |
| dateFrom, dateTo | Przedział dat zdarzenia, włącznie |
| location | Fragment nazwy miejsca, bez rozróżniania wielkości liter |
| longitude, latitude, radiusMeters | Komplet parametrów wyszukiwania w promieniu 1–100000 m |
| page, size | Paginacja od strony 0; rozmiar 1–100, domyślnie 20 |

Filtry można łączyć. Wyniki są sortowane od najnowszych, a przy wyszukiwaniu
w promieniu — od najbliższych. Remisy rozstrzyga UUID.
GET /api/reports/mine obsługuje te same filtry dla własnych zgłoszeń.

Obecne q nie zapewnia rankingu trafności tekstowej, korekty literówek,
normalizacji polskiej odmiany ani wyszukiwania po znaczeniu. Embeddingi
są już wykorzystywane w automatycznym matchingu, ale lupka jeszcze ich nie używa.

Kod: [filtry](../foundly-backend/src/main/java/pl/szymonprofic/foundlybackend/dto/ReportFilter.java),
[predykaty wyszukiwania](../foundly-backend/src/main/java/pl/szymonprofic/foundlybackend/repositories/specifications/ReportSpecifications.java),
[sortowanie i odpowiedzi](../foundly-backend/src/main/java/pl/szymonprofic/foundlybackend/services/ReportService.java).

## 3. Wybór technologii

Na obecnym etapie proponujemy wykorzystać PostgreSQL:

- FTS do wyszukiwania słów i fraz w tytule i opisie;
- unaccent do normalizacji znaków diakrytycznych;
- pg_trgm jako uzupełnienie dla literówek i podobnych zapisów;
- pgvector i istniejący embedding-service do późniejszego wyszukiwania semantycznego.

Nie zapadła decyzja o dodaniu Elasticsearch. W tym projekcie wymagałby osobnej
usługi oraz niezawodnej synchronizacji dodawania, edycji, zakończenia i usuwania
ogłoszeń. Trzeba byłoby obsłużyć ponowienia, odbudowę indeksu i opóźnienia
widoczności zmian. Powrót do tego wyboru powinien wynikać z brakujących funkcji
lub pomiarów jakości i wydajności, nie z samej liczby rekordów.

Elasticsearch ma m.in. dedykowane mechanizmy
[search-as-you-type](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/search-as-you-type)
i [plugin Stempel dla polskiego tekstu](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-stempel).
To opcja na dalszy rozwój. Obecne 174 ogłoszenia nie wystarczają do oceny
wydajności przy docelowym obciążeniu.

## 4. Pierwszy etap: PostgreSQL FTS

FTS jest wbudowane w PostgreSQL; samo jego użycie nie wymaga osobnego rozszerzenia.
Proponowane elementy implementacji:

1. Reprezentacja tytułu i opisu jako tsvector, np. w polu search_vector tabeli report.
2. Większa waga dopasowania w tytule, mniejsza w opisie, ustawiana przez setweight.
3. Indeks GIN dla wyszukiwania tekstowego.
4. Parsowanie zapytania przez websearch_to_tsquery i ranking np. ts_rank_cd.
5. Automatyczne odświeżanie reprezentacji przy zmianie tytułu lub opisu oraz
   uzupełnienie jej dla już istniejących ogłoszeń.

Obsługiwana przez websearch_to_tsquery składnia obejmuje:

| Wpis użytkownika | Znaczenie |
|---|---|
| czarny portfel | Oba słowa, bez wymagania ich bezpośredniego sąsiedztwa |
| "niebieski brelok" | Fraza |
| portfel OR saszetka | Alternatywa |
| klucze -samochodowe | Wykluczenie słowa |

Szczegóły opisuje dokumentacja
[parsowania i rankingu FTS](https://www.postgresql.org/docs/18/textsearch-controls.html)
oraz [indeksów GIN](https://www.postgresql.org/docs/18/textsearch-indexes.html).

Chcemy zachować obecny endpoint, filtry i paginację. Przy podanym q proponujemy
sortowanie według trafności, z deterministycznym rozstrzyganiem remisów. Przed
wdrożeniem trzeba ustalić pierwszeństwo trafności i odległości, gdy użytkownik
jednocześnie poda zapytanie oraz promień wyszukiwania. Zmiana q z fragmentu tekstu
na zapytanie FTS jest zmianą zachowania API i wymaga testów oraz aktualizacji dokumentacji.

## 5. Polski tekst i literówki

W sprawdzonej lokalnej bazie unaccent i pg_trgm są dostępne do włączenia,
ale nie są jeszcze zainstalowane. Nie ma konfiguracji wyszukiwania polish.

- [unaccent](https://www.postgresql.org/docs/18/unaccent.html) pozwoli traktować
  np. `żółty` i `zolty` w sposób zgodny po normalizacji. Dokumenty i zapytania
  muszą korzystać z tej samej normalizacji.
- Konfiguracja simple z unaccent jest prostym punktem startowym, ale nie zapewnia
  polskiej odmiany: `portfel` i `portfelem` nie staną się automatycznie jednym słowem.
  Pełniejsza obsługa wymaga odpowiedniego
  [słownika tekstowego](https://www.postgresql.org/docs/18/textsearch-dictionaries.html).
- [pg_trgm](https://www.postgresql.org/docs/18/pgtrgm.html) można wykorzystać
  do literówek, np. `portfl`. Strategię dołączania takich wyników i próg podobieństwa
  trzeba przetestować; podobieństwo znaków nie oznacza podobieństwa znaczenia.

Przed migracją należy wybrać sposób utrzymywania search_vector zgodny z wybraną
normalizacją: konfigurację FTS, trigger lub inne rozwiązanie. Nie zakładamy jeszcze
konkretnego mechanizmu kolumny wyliczanej.

## 6. Wyszukiwanie semantyczne z lupki

Możemy wykorzystać obecny model sdadas/mmlw-roberta-base, embedding-service
i PostgreSQL z pgvector. Proponowany przepływ:

1. Frontend przesyła tekst do backendu.
2. Backend przygotowuje zapytanie i generuje jeden embedding tego zapytania.
3. PostgreSQL porównuje go z aktualnymi, zgodnymi wektorami ogłoszeń.
4. Backend zwraca wyniki według podobieństwa z wybranymi filtrami i paginacją.

Wektory ogłoszeń są przygotowywane wcześniej w tle, nie przy każdym wyszukiwaniu.
Frontend nie powinien bezpośrednio wywoływać embedding-service.
EmbeddingJobProcessor i MatchingWorker pozostają elementami przetwarzania zadań;
żądanie wyszukiwania potrzebuje osobnej ścieżki obsługi w backendzie.

### Role embeddingów i prefiks zapytania

Używany model wymaga prefiksu `zapytanie: ` dla zapytań. Wyszukiwane dokumenty
są kodowane bez niego, zgodnie z [instrukcją modelu](https://huggingface.co/sdadas/mmlw-roberta-base).

| Tekst | Obecnie | Potrzeba wyszukiwarki |
|---|---|---|
| Zapytanie wpisane w lupce | Brak generowania wektora | Wektor z prefiksem zapytanie: |
| Ogłoszenie FOUND | Wektor dokumentu bez prefiksu | Możliwość ponownego użycia zgodnego wektora |
| Ogłoszenie LOST | Wektor z prefiksem zapytanie: | Dodatkowa reprezentacja dokumentu bez prefiksu |

Obecny EmbeddingTextBuilder buduje tekst z tytułu, opisu i opcjonalnego koloru,
normalizuje NFC i białe znaki, wylicza hash oraz stosuje prefiks zależny od typu.
Nie należy bez planu nadpisywać obecnych wektorów LOST, bo używa ich matching.

report_embedding ma obecnie jeden rekord na report_id. Dwie role dla LOST
wymagają zaprojektowania zmiany schematu, np. dodatkowej reprezentacji lub jawnego
rozróżnienia ról. To decyzja przed implementacją, nie już wdrożone rozwiązanie.
Trzeba zachować zgodność model_id, model_revision, wymiarów, roli i wersji
przygotowania tekstu oraz kontrolę hashy po edycji.

## 7. Docelowe połączenie metod

Proponowany kierunek to wyszukiwanie hybrydowe:

- FTS dobrze służy konkretnym słowom, markom i oznaczeniom modeli;
- embeddingi pozwalają uwzględniać podobne znaczenie mimo innych słów;
- filtry kategorii, typu, daty i odległości ograniczają zbiór wyników.

Sposób połączenia list lub ocen pozostaje do ustalenia. Nie należy bez kalibracji
dodawać do siebie ts_rank_cd i cosine, ponieważ ich skale i znaczenie są różne.
Nie ustaliliśmy jeszcze parametru wyboru trybu ani nowego kontraktu odpowiedzi.

Próg 0,88 rozważany dla matchingu LOST/FOUND nie jest automatycznie progiem
wyszukiwarki. Krótkie zapytania trzeba ocenić na osobnym zestawie zapytanie →
trafne ogłoszenia. Ranking lupki nie powinien również bez decyzji produktowej
dziedziczyć wag daty, lokalizacji i kategorii z automatycznego matchingu.

## 8. Bezpieczeństwo i zachowanie przy błędach

Obecna ścieżka q przekazuje wzorzec jako wartość Criteria API; Hibernate wiąże
takie wartości jako parametry JDBC. Nie doklejamy treści użytkownika do tekstu SQL.
Escapowanie %, _ i ! w obecnym LIKE kontroluje znaczenie wzorca i jest osobną
kwestią od ochrony przed SQL injection.

Także przy FTS i zapytaniach wektorowych należy używać parametrów, np.
websearch_to_tsquery('simple', :q). Sam parser zapytań FTS nie zastępuje
parametryzacji SQL. Obsługiwane opcje sortowania powinny mieć jawny, zamknięty zestaw.

Przed etapem semantycznym trzeba ustalić:

- timeout i zachowanie przy niedostępności embedding-service;
- ograniczenie liczby wywołań, debounce w lupce i ewentualny cache zapytań;
- sposób pokazania świeżych ogłoszeń, których embedding jeszcze nie powstał;
- kontrolę aktualności wektorów po edycji oraz zachowanie filtrów i uprawnień;
- czy awaria części semantycznej pozwala zwrócić wyniki FTS i jak zakomunikować tryb.

## 9. Kolejność wdrażania i weryfikacja

- [ ] Etap 1: FTS dla tytułu i opisu, większa waga tytułu, GIN, normalizacja
  polskich znaków, backfill i aktualizacja przy edycji.
- [ ] Etap 2: dopracowanie polskiej odmiany i literówek na rzeczywistych zapytaniach.
- [ ] Etap 3: reprezentacje dokumentowe dla obu typów ogłoszeń i generowanie
  embeddingu zapytania w backendzie; zachowanie działania dotychczasowego matchingu.
- [ ] Etap 4: połączenie wyników tekstowych i semantycznych oraz pomiar jakości.
- [ ] Osobny zestaw testowy: słowa w tytule i opisie, frazy, OR, wykluczenia,
  polskie znaki, odmiana, literówki, marki/modele, synonimy i brak trafnych wyników.
- [ ] Testy API: filtry, stabilna paginacja, sortowanie, walidacja, dane własne
  i publiczne oraz tekst przypominający SQL traktowany jako dane.
- [ ] Testy integracyjne: aktualizacja indeksu po edycji, zgodność ról i wersji
  wektorów, ogłoszenia bez aktualnego embeddingu i awaria embedding-service.
- [ ] Pomiar czasu odpowiedzi i obciążenia przy reprezentatywnej liczbie ogłoszeń
  i równoczesnych zapytań. Na tej podstawie ewentualny powrót do Elasticsearch.

## Powiązane notatki

- [Dokumentacja backendu](../foundly-backend/README.md)
- [Plan automatycznego matchingu](automatic-report-matching.md)
- [TODO dotyczące jakości matchingu](../todo.md)
- [Dane developerskie do oceny matchingu](../scripts/dev-matching/README.md)
