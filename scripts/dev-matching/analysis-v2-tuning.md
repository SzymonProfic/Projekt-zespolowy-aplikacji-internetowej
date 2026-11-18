# Ocena zestawu v2 do strojenia

Analiza dotyczy części `tuning` (105 ogłoszeń). Validation nie została oceniona.
Wykorzystano `fixtures.json`, `baseline-v2-tuning.md` i lokalny plik
`/tmp/foundly-matching-evaluation-v2/tuning/pairs.csv` z pomiaru 2026-11-13.
Konfiguracja aplikacji, dane w bazie i embeddingi nie zostały zmienione.

## Co działa

W części do strojenia jest 30 LOST mających poprawny odpowiednik oraz 3 LOST bez
odpowiednika. Dla 27 z tych 30 poprawny przedmiot jest pierwszy. Dla wszystkich
30 poprawny przedmiot znajduje się w pierwszej trójce. Zapisano wszystkie
35 oczekiwanych poprawnych par, uwzględniając dodatkowe warianty scenariuszy.

Sam fakt zapisania wszystkich poprawnych par nie wystarcza: obecny próg 0,5
przepuszcza również wiele błędnych sugestii. Przy progu końcowej oceny 0,90
w zapisanych parach pozostają 32 poprawne i 22 błędne sugestie.

## Próg semantyczny

Poniższa symulacja używa surowych podobieństw wszystkich 2306 par o etykietach
MATCH/NO_MATCH w części tuning, a nie tylko par utrwalonych przez worker.
UNCERTAIN i EXCLUDED są pominięte. Obie strony pary należą do tuning.

| Minimalna semantyka | Poprawne / 35 | Błędne | Precision | Recall |
|---|---:|---:|---:|---:|
| 0,85 | 35 | 29 | 54,7% | 100% |
| 0,88 | 35 | 15 | 70,0% | 100% |
| 0,90 | 30 | 6 | 83,3% | 85,7% |
| 0,91 | 24 | 2 | 92,3% | 68,6% |
| 0,92 | 21 | 0 | 100% | 60,0% |

Wniosek z małego v1, że 0,90 zachowuje wszystkie poprawne pary, nie utrzymał się
na większym zestawie. Próg 0,92 usuwa błędne pary w tej próbie, ale traci 14
poprawnych. Zero błędów na tych danych nie gwarantuje braku błędów produkcyjnych.

0,88 jest kandydatem do eksperymentu dla listy sugestii: zachowuje wszystkie
poprawne pary, lecz przepuszcza 15 błędnych oraz 2 niejednoznaczne. Te dwie
niejednoznaczne pary nie są wliczone do precision. Najsłabsza poprawna para ma
semantykę około 0,88095, więc margines nad progiem jest mały. Nie należy traktować
0,88 jako ustalonej wartości produkcyjnej.

## Zmiana wag

Przeliczono offline pełne pary tuning, używając tej samej normalizacji co
MatchingScoreCalculator: wykładniczy zanik lokalizacji i daty, tolerancja daty
znalezienia wcześniejszej o dwa dni oraz pominięcie nieznanej kategorii OTHER.
Dla aktualnych wag wynik sprawdzono względem wszystkich zapisanych ocen
MATCH/NO_MATCH; różnica była mniejsza niż 1e-12.

Przy bramce semantycznej 0,88:

| Semantyka / lokalizacja / data / kategoria | Poprawny top 1 / 30 | Poprawny w top 3 / 30 |
|---|---:|---:|
| 60 / 20 / 10 / 10 — obecnie | 27 | 30 |
| 70 / 15 / 10 / 5 | 27 | 30 |
| 75 / 15 / 5 / 5 | 27 | 30 |
| 80 / 10 / 5 / 5 | 27 | 30 |
| 90 / 5 / 2,5 / 2,5 | 27 | 30 |
| 100 / 0 / 0 / 0 | 27 | 30 |

To symulacja rankingu wszystkich kwalifikujących się par tuning; nie odtwarza
kolejności publikacji zadań ani historycznego limitu top 10 przy zapisie.
Nie ma na tej podstawie uzasadnienia do zmiany wag. Większa waga semantyki
nie rozwiązuje błędnego porządku, jeżeli właściwy opis ma niższy cosine.

Przykłady:

- Ładowarka: poprawna para 0,8925, inna ładowarka 0,9028.
- Smycz: poprawna para 0,8810, inna smycz 0,8875.
- Czytnik: poprawny 0,89672, inny 0,89657 — bardzo mały margines.
- Pilot Toyota: ogólny opis bez cech szczególnych wyprzedza poprawny odpowiednik.

W trudnych parach miejsce, data i kategoria są takie same. Zmiana ich wag
nie może rozróżnić sprzecznych cech opisów. Twarda zgodność kategorii przy
semantyce 0,88 traci 2 poprawne pary, pozostawiając wszystkie 15 błędnych.

## Proponowana kolejność zmian

1. Przetestować min-semantic-score=0.88 dla sugestii do ręcznego obejrzenia.
   Pozostawić obecne wagi i nie ustalać na tej podstawie progu powiadomień.
2. Uzupełnić przykłady, w których lokalizacja i data realnie rozstrzygają między
   podobnymi przedmiotami. Obecny zestaw ma wiele negatywów o identycznych
   sygnałach pozatekstowych, więc słabo uzasadnia optymalizację tych wag.
3. Dodać osobną ocenę zgodności cech w krótkiej liście kandydatów: np. model,
   rozmiar, rodzaj złącza. Jawne sprzeczności mogą obniżać wynik lub powodować
   oznaczenie do weryfikacji. Sam brak cechy nie oznacza sprzeczności; różnica
   koloru również nie powinna być automatycznym dowodem innego przedmiotu.
4. Po ustaleniu konkretnej konfiguracji wykonać ponowną analizę przez worker
   i ocenić validation. Restart backendu nie przelicza zadań COMPLETED.
5. Oddzielnie ustalić politykę powiadomień, uwzględniając koszt błędnych sugestii.

Wyniki dotyczą syntetycznego zestawu o znanym inwentarzu i pomijają pary między
tuning i validation. Nie stanowią oceny skuteczności całego API ani kalibracji
prawdopodobieństwa, że dwa ogłoszenia dotyczą tego samego przedmiotu.
