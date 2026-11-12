# Foundly — zgłoszenia

Frontend zawiera listę ogłoszeń z filtrami, widok szczegółów oraz formularze tworzenia i edycji. Logowanie i rejestrację obsługuje Keycloak, a zapis zgłoszeń i odczyt szczegółów — REST API Foundly. Aplikacja wykorzystuje React, TypeScript, Vite, React Router i ikony Lucide. Wymagany jest Node.js 22.12+ z gałęzi 22 lub Node.js 24+.

Do sprawdzenia zapisu uruchom backend, bazę i Keycloak. Z głównego katalogu repozytorium przygotuj plik `.env` na podstawie `.env.example` (jeśli jeszcze go nie masz), ustaw lokalne hasła, a następnie wykonaj:

```bash
docker compose --profile app up -d --build
```

W drugim terminalu, z głównego katalogu repozytorium:

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
| `npm test` | Sprawdza walidację formularza, format danych i warunki dostępności edycji. |

Przy ręcznej weryfikacji zaloguj się [kontem demonstracyjnym](../foundly-backend/README.md#konto-demonstracyjne), dodaj zgłoszenie i otwórz jego szczegóły z listy. Sprawdź podpowiedzi po wysłaniu pustego formularza, a następnie edytuj zapisane zgłoszenie. Otwórz edycję w dwóch kartach: po zapisie w pierwszej starszy zapis w drugiej powinien zostać odrzucony, a wpisane wartości powinny pozostać w formularzu.

## Widoki i zapis

| Adres | Działanie |
| --- | --- |
| `/` | Lista i filtry ogłoszeń. Przykładowe karty mogą pojawić się przy pustych wynikach i nie mają adresów w API. |
| `/ogloszenia/:id` | Publiczny odczyt szczegółów. Przycisk „Edytuj zgłoszenie” widzi autor aktywnego zgłoszenia. |
| `/ogloszenia/nowe` | Dodawanie zgłoszenia po zalogowaniu. |
| `/ogloszenia/:id/edytuj` | Edycja aktywnego zgłoszenia przez autora. Typ zgłoszenia pozostaje bez zmian. |

Formularze korzystają ze wspólnego komponentu i pobierają kategorie z API. Wymagają nazwy, kategorii, opisu, daty i lokalizacji ze współrzędnymi; kolor jest opcjonalny. Data nie może być przyszła według strefy `Europe/Warsaw`. Dokładne współrzędne są pobierane przy edycji tylko dla autora.

Po zapisie aplikacja przechodzi do szczegółów i potwierdza powodzenie. Brak zgłoszenia ma własny widok, a błędy zapisu pozostawiają dane w formularzu. Edycja przekazuje numer wersji otrzymany z API; przy konflikcie `409` trzeba wrócić do szczegółów i pobrać aktualne dane. [Format żądań dodawania i edycji](../foundly-backend/README.md#dodanie-i-edycja).

## Struktura źródeł

```text
src/
├── components/
│   ├── AccountSession/      # Logowanie, rejestracja i profil
│   ├── ReportCard/          # Karta ogłoszenia
│   ├── ReportForm/          # Wspólny formularz dodawania i edycji
│   └── ReportPage/          # Ramka widoków i komunikaty pobierania
├── demo/
│   └── reports.ts           # Trzy przykładowe ogłoszenia
├── hooks/                   # Pobieranie szczegółów, ponawianie i anulowanie żądań
├── pages/                   # Lista, szczegóły, dodawanie i edycja
├── services/                # Wywołania API i sesja Keycloak
├── types/                   # Typy ogłoszeń i danych wysyłanych do API
├── utils/reportForm.ts      # Walidacja i mapowanie danych formularza
├── App.tsx                  # Trasy i wspólny pasek konta
├── index.css                # Układ i style listy ogłoszeń
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
| `detailsTo` | `string` | Nie | Adres szczegółów. W kontekście routera wyświetla link „Zobacz szczegóły” zamiast przycisku „Wybierz”. |

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

## Logowanie i profil

Przycisk **Utwórz konto** obok **Zaloguj się** otwiera formularz rejestracji Keycloak przez `keycloak.register()`. Formularze mają [motyw Foundly](../docker/keycloak/themes/foundly/README.md), domyślnie po polsku, z możliwością zmiany na angielski. Po utworzeniu konta użytkownik wraca na stronę aplikacji; synchronizacja profilu działa tak samo jak po logowaniu. Motyw i rejestrację dla nowego lub istniejącego realmu ustawia `docker compose up -d keycloak-init` z głównego katalogu projektu.

Uruchom backend i Keycloak zgodnie z [instrukcją backendu](../foundly-backend/README.md), a frontend przez `npm run dev`. Otwórz `http://localhost:5173` i wybierz **Zaloguj się**. Konto demonstracyjne i jego hasło są opisane w instrukcji backendu; istniejące konto zachowuje dotychczasowe hasło.

Adapter `keycloak-js` używa Authorization Code z PKCE S256 i zakresów `openid profile email`. Po zalogowaniu lub przywróceniu sesji frontend wywołuje `PUT /api/me` bez body; ekran konta pokazuje nazwę i e-mail dopiero po poprawnej odpowiedzi. Synchronizacja jest ponawiana po odświeżeniu tokenu. Przy błędzie API można wybrać **Spróbuj ponownie**. Wylogowanie usuwa lokalny stan profilu, a spóźniona odpowiedź nie przywraca sesji. Tokeny są trzymane w pamięci adaptera, bez zapisywania w localStorage.

Domyślne adresy to backend `http://localhost:8080` i Keycloak `http://localhost:8081`, realm `foundly`. Można je zmienić w lokalnym pliku `.env.local` na podstawie [.env.example](.env.example): `VITE_API_URL`, `VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM`. Przy zmianie origin frontendu trzeba również zmienić callback i Web Origins klienta w Keycloak oraz CORS backendu. `npm run preview` używa innego portu, którego domyślna konfiguracja logowania nie obejmuje.

Implementacja sesji jest w `src/services/auth.ts`, a widok konta w `src/components/AccountSession/`. Formularze używają tej samej sesji do pobrania aktualnego tokenu przed zapisem.

Automatyczne testy synchronizacji sesji zostaną dodane w osobnym sprincie.
