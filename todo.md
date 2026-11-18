# TODO — dopracowanie automatycznego matchingu LOST/FOUND

Lista sugestii po analizie zestawu testowego v2. Kolejne punkty wdrażamy etapami,
porównując wynik z poprzednią wersją. Ten dokument nie zmienia konfiguracji,
danych ani działania aplikacji.

## Punkt wyjścia

Obecnie działają: osobny embedding-service, zapis wektorów w pgvector,
trwałe zadania matching_job, asynchroniczny worker z ponowieniami,
ranking semantyka/lokalizacja/data/kategoria, zapis i wygaszanie par oraz
GET /api/reports/{id}/matches dostępny dla autora zgłoszenia.

Zestaw developerski obejmuje 174 ogłoszenia: 105 do strojenia i 69 do walidacji.
Wyniki walidacyjne nie zostały jeszcze ocenione. W części do strojenia:

- Poprawny odpowiednik jest pierwszy dla 27 z 30 LOST mających odpowiednik.
- Dla wszystkich tych 30 LOST poprawny odpowiednik jest w pierwszej trójce.
- Semantyka >= 0,88 zachowuje 35 poprawnych par i przepuszcza 15 błędnych.
- Semantyka >= 0,90 zachowuje 30 poprawnych par i przepuszcza 6 błędnych.
- Semantyka >= 0,92 zachowuje 21 poprawnych par i nie przepuszcza błędnych w tej próbie.
- Sprawdzone warianty wag nie poprawiły pierwszego wyniku.

To wyniki syntetycznych par wewnątrz tuning, nie całego ruchu aplikacji.
Niejednoznaczne przypadki nie są zaliczane do błędnych par w tych liczbach.
Ocena 0,90 nie oznacza 90% prawdopodobieństwa zgodności przedmiotów.

## P0 — najbliższy etap: pomiar i konfiguracja

- [ ] Przetestować min-semantic-score=0.88 jako próg dopuszczenia sugestii do
  ręcznej oceny. Początkowo zachować wagi 60/20/10/10. Próg jest eksperymentalny:
  najsłabsza poprawna para ma około 0,88095, więc margines jest mały.
- [ ] Udostępnić parametry rankingu w konfiguracji środowiska i sprawdzić całą
  ścieżkę .env → Compose → Spring. Zapisywać kompletną konfigurację przy każdym
  eksperymencie: wagi, skale, progi, limit, wersję rankingu i modelu.
- [ ] Dodać kontrolowane ponowne zlecenie analizy po zmianie rankingu. Zwiększać
  ranking_version przy zmianie polityki i zapobiegać mieszaniu starych ocen
  z nowymi. Sam restart nie przelicza zadań COMPLETED. Zgodne embeddingi zachować.
- [ ] Rozszerzyć evaluator o tabelę progów semantycznych i kombinacji progów
  z wagami; rozróżnić wynik symulacji od wyniku faktycznie opublikowanego przez worker.
- [ ] Oddzielnie mierzyć recall kandydatów, poprawny wynik w top 1/top 3,
  precision sugestii i liczbę fałszywych sugestii dla LOST bez odpowiednika.
  W raporcie podawać liczbę ogłoszeń i poprawnych par, nie tylko procenty.
- [ ] Doprecyzować ocenę par między tuning i validation. Obecny raport je pomija,
  a API je pokazuje. Strojenie prowadzić na izolowanym zbiorze tuning; po zamrożeniu
  konfiguracji wykonać również ocenę całego przepływu i limitów kandydatów.

**Kryterium zakończenia:** można odtworzyć eksperyment i porównać dwie konfiguracje
na tych samych danych, bez regenerowania zgodnych wektorów i bez utraty decyzji.

## P1 — jakość opisów i rozróżnianie podobnych przedmiotów

- [ ] Przejrzeć ręcznie etykiety MATCH/NO_MATCH/UNCERTAIN. Nie traktować ogólnego
  opisu jako pewnego negatywu tylko dlatego, że brakuje cech rozpoznawczych.
- [ ] Dodać przykłady pisane niezależnie przez różne osoby: krótkie, potoczne,
  z literówkami, brakami i sprzecznymi szczegółami. Dane syntetyczne zachować
  jako powtarzalny zestaw regresyjny.
- [ ] Uzupełnić scenariusze, w których lokalizacja lub data rzeczywiście rozstrzyga
  między podobnymi opisami. Wiele obecnych trudnych negatywów ma identyczne miejsce,
  datę i kategorię, więc nie pozwala dobrze dobrać ich wag.
- [ ] Sprawdzić zgodność istotnych cech na krótkiej liście kandydatów:
  marka/model, rozmiar, liczba elementów, rodzaj złącza, charakterystyczne uszkodzenia.
  Zacząć od jawnych i łatwych do wyjaśnienia rozbieżności.
- [ ] Rozróżnić brak informacji od sprzeczności. Nie odrzucać automatycznie pary
  tylko przez inne określenie koloru lub brak jednej cechy w opisie FOUND.
- [ ] Nie wymuszać bezwzględnie tej samej kategorii: przy semantyce 0,88 taki filtr
  usuwa 2 poprawne pary, pozostawiając wszystkie 15 błędnych w obecnej próbie.
- [ ] Ocenić, czy prosty etap porównania cech wystarcza. Dodatkowy model oceniający
  wspólnie dwa opisy rozważyć dopiero po pomiarze jakości, opóźnienia i kosztu.

**Przypadki regresyjne:** poprawna ładowarka przegrywa z inną ładowarką, poprawna
smycz przegrywa z inną smyczą, a ogólny opis pilota wyprzedza właściwy odpowiednik.
Zmiana samych wag nie rozwiązuje tych problemów.

## P1 — walidacja przed przyjęciem konfiguracji

- [ ] Ustalić przed pomiarem, jaki kompromis między utraconymi parami a błędnymi
  sugestiami akceptujemy. Osobno określić wymagania dla listy i dla powiadomień.
- [ ] Zamrozić konfigurację wybraną na tuning i dopiero wtedy ocenić validation.
  Jeśli na tej podstawie ponownie zmienimy parametry, przygotować nowy zbiór
  końcowej oceny; nie nazywać ponownie wykorzystanego zestawu niezależną walidacją.
- [ ] Zachować wszystkie warianty jednego przedmiotu w jednym podzbiorze.
  Rozszerzać liczbę niezależnych przedmiotów, nie tylko liczbę wariantów opisu.
- [ ] Przy zmianie modelu lub przygotowania tekstu porównywać jakość na tym samym
  zestawie i używać wyłącznie zgodnych wektorów. Zaplanować generowanie nowych
  wektorów i możliwość powrotu do poprzedniej wersji.

## P2 — decyzje użytkowników i interfejs

- [ ] Dodać widok dopasowań przy własnym ogłoszeniu, z paginacją i przybliżoną
  odległością. Prezentować sugestię, bez komunikatu sugerującego pewne odzyskanie.
- [ ] Pokazać, dlaczego wynik jest wysoko: podobny opis, bliska lokalizacja,
  zgodna data; wskazać również niepewność lub rozbieżność cech.
- [ ] Dodać osobne decyzje obu autorów, powiązane z wersją ocenianej pary.
  Akceptacja jednej strony nie powinna potwierdzać decyzji drugiej.
- [ ] Zapewnić autoryzację i sprawdzenie aktualności przy zapisie decyzji.
  Obsłużyć konflikt wersji oraz edycję, zakończenie lub usunięcie ogłoszenia
  w trakcie podejmowania decyzji. Nie nadpisywać decyzji automatycznym rankingiem.
- [ ] Zbierać powody odrzucenia, np. inny model lub kolor, do przyszłych ocen
  jakości. Sam brak reakcji użytkownika nie oznacza błędnego dopasowania.

## P2 — powiadomienia po ustaleniu jakości

- [ ] Oddzielić próg dopuszczenia kandydata, kolejność rankingu i próg powiadomienia.
  Próg dla powiadomień dobrać na osobnej ocenie; 0,92 nie jest jeszcze ustalonym
  progiem produkcyjnym pomimo braku błędów w aktualnej próbie.
- [ ] Dodać powiadomienia w aplikacji dla obu autorów. Zapis powiadomienia powiązać
  transakcyjnie z publikacją aktualnej pary, żeby retry nie gubił ani nie mnożył powiadomień.
- [ ] Wymusić unikalność odbiorca + para + typ zdarzenia. Ponowne przeliczenie
  tej samej sugestii nie powinno ponownie powiadamiać użytkownika.
- [ ] Dodać odczyt własnych powiadomień i idempotentne oznaczenie jako przeczytane.
  Obsłużyć odnośnik do pary, która później wygasła lub została usunięta.
- [ ] E-mail i push dodać później, z osobnymi próbami dostarczenia i ponowieniami.
  Odczyt powiadomienia w aplikacji nie oznacza dostarczenia zewnętrznym kanałem.

## P3 — utrzymanie, prywatność i wydajność

- [ ] Mierzyć opóźnienie kolejki, czas generowania embeddingu i publikacji,
  liczbę retry/DEAD oraz czas oczekiwania na blokadę publikacji. Przygotować
  bezpieczne ponowienie nieudanych zadań i diagnostykę bez logowania tokenów i opisów.
- [ ] Zmierzyć wpływ skanowania kandydatów i serializacji publikacji na większej
  bazie. Dopiero po pomiarach rozważyć HNSW, zawężanie kandydatów lub zmianę blokad.
  Kontrolować przy tym, czy nie tracimy poprawnych par przez przybliżone wyszukiwanie.
- [ ] Ustalić osobno limit kandydatów do obliczeń, limit zapisu na zadanie i limit
  sugestii widocznych dla użytkownika. Obecne top 10 na zadanie nie oznacza
  maksymalnie 10 par powiązanych z ogłoszeniem.
- [ ] Przejrzeć publiczną dokładną lokalizację. Wszystkie obecne ogłoszenia
  testowe mają współrzędne i ranking ich używa; locationPoint=null na liście
  nie oznacza braku danych. Szczegóły publiczne nadal ujawniają dokładny punkt.
- [ ] Ustalić zakres publicznych cech przedmiotu, informacji przekazywanych do
  embedding-service i danych potrzebnych dopiero do weryfikacji właściciela.
- [ ] Jeżeli produkt ma obsługiwać brak lokalizacji lub przybliżoną datę,
  zaprojektować jawne pola, walidację i testy brakujących danych. Obecny schemat
  wymaga lokalizacji i daty, więc obecny zestaw nie weryfikuje tych scenariuszy.
- [ ] Rozszerzyć testy odpowiednio do zmian: regresje jakości, aktualność przy
  edycji i awarii, niezależne decyzje, deduplikacja powiadomień i kontrola dostępu.
  Zachować istniejące testy kolejki, współbieżności i idempotencji.

## Materiały

- [Analiza jakości v2](scripts/dev-matching/analysis-v2-tuning.md)
- [Pomiar bazowy v2](scripts/dev-matching/baseline-v2-tuning.md)
- [Dane testowe i polecenia](scripts/dev-matching/README.md)
- [Katalog scenariuszy i ID](scripts/dev-matching/catalog.md)
- [Dokumentacja backendu](foundly-backend/README.md)
- [Pierwotny plan architektury](docs/automatic-report-matching.md) — zawiera
  również propozycje i historyczny stan projektu, nie tylko już wdrożone funkcje.
