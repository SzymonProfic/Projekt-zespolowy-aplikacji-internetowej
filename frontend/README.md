**Foundly — karta ogłoszenia**

Pierwszy etap frontendu: komponent `ReportCard` z własnymi stylami oraz mały podgląd trzech przykładowych kart. Wykorzystuje React, TypeScript, Vite i ikony Lucide. Wymagany Node.js 22.12+ z gałęzi 22 lub Node.js 24+.

Z tego katalogu wykonaj:

```bash
cd frontend
npm ci
npm run dev
```

Otwórz adres wypisany przez Vite, zwykle `http://localhost:5173`. Serwer zatrzymasz przez `Ctrl+C`. Przy kolejnych uruchomieniach, jeśli zależności są już zainstalowane i lockfile się nie zmienił, wystarczy `npm run dev` z katalogu `frontend`.

## Sprawdzenie i kompilacja

Wszystkie poniższe polecenia wykonuj w katalogu `frontend`.

| Polecenie | Działanie |
| --- | --- |
| `npm run typecheck` | Sprawdza typy TypeScript bez generowania plików. |
| `npm run build` | Sprawdza typy i tworzy wersję produkcyjną w `dist/`. |
| `npm run preview` | Uruchamia lokalny podgląd wcześniej zbudowanej wersji. |

Przy ręcznej weryfikacji sprawdź etykiety oraz statusy trzech kart, aktualizację komunikatu po kliknięciu „Wybierz” i układ na szerokim oraz wąskim ekranie. Projekt nie zawiera obecnie automatycznego zestawu testów komponentów.

## Struktura źródeł

```text
src/
├── components/
│   └── ReportCard/
│       ├── ReportCard.tsx   # Komponent karty ogłoszenia
│       └── ReportCard.css   # Style karty
├── demo/
│   └── reports.ts           # Trzy przykładowe ogłoszenia
├── types/
│   └── report.ts            # Typ danych Report
├── App.tsx                 # Strona demonstracyjna i obsługa wyboru karty
├── index.css               # Układ i style strony demonstracyjnej
└── main.tsx                # Punkt wejścia aplikacji React
```

## Użycie komponentu

Przykład w pliku umieszczonym bezpośrednio w `src/`:

```tsx
import { ReportCard } from './components/ReportCard/ReportCard';
import type { Report } from './types/report';

const report: Report = {
  id: '1',
  title: 'Klucze z niebieskim brelokiem',
  type: 'found',
  category: 'Klucze',
  location: 'Kraków, Stare Miasto',
  date: '2026-06-18',
  color: 'Niebieski',
  description: 'Znaleziono pęk kluczy przy ławce w Plantach.',
};

export function Example() {
  return <ReportCard report={report} />;
}
```

| Właściwość | Typ | Wymagana | Znaczenie |
| --- | --- | --- | --- |
| `report` | `Report` | Tak | Dane wyświetlanego ogłoszenia. |
| `onSelect` | `(report: Report) => void` | Nie | Funkcja wywoływana po wyborze karty. Jej przekazanie wyświetla przycisk „Wybierz”. |

Przykład obsługi wyboru:

```tsx
<ReportCard
  report={report}
  onSelect={(selectedReport) => console.log(selectedReport.id)}
/>
```

`Report` zawiera pola `id`, `title`, `type`, `category`, `location`, `date`, `color` i `description` oraz opcjonalne `owner` i `recovered`. Data zdarzenia ma format `YYYY-MM-DD`; na karcie jest formatowana po polsku. Niepoprawna data wyświetla tekst „Brak daty”.

Typ `lost` oznacza zgubienie, a `found` — znalezienie przedmiotu. `recovered: true` wyświetla status „Zakończone”; w pozostałych przypadkach karta pokazuje „Aktywne ogłoszenie”.

Obsługiwane kategorie to: Elektronika, Klucze, Portfele i dokumenty, Torby i plecaki, Biżuteria oraz Inne. Dla nieznanej kategorii używana jest ikona paczki. Pola `color` i `owner` zachowano w modelu na potrzeby rozbudowy; nie zmieniają obecnie wyglądu karty.

## Aktualne ograniczenia i dalsza praca

Podgląd korzysta wyłącznie z lokalnych danych demonstracyjnych. Wybranie karty aktualizuje komunikat na stronie; nie otwiera jeszcze widoku szczegółów ani nie zapisuje wyboru po odświeżeniu.

Kolejne etapy obejmują nagłówek i nawigację, listę ogłoszeń z wyszukiwaniem i filtrami, formularze oraz widok szczegółów. Logowanie, zapis danych i integracja z backendem wymagają osobnej implementacji. Dane przykładowe nie potwierdzają działania tych funkcji.
