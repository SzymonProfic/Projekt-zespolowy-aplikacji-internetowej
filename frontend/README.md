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
| `npm test` | Testy klienta HTTP, autoryzacji, serwisów API, sesji i synchronizacji profilu oraz walidacji formularza, formatu danych i warunków dostępności edycji. |
| `npm run typecheck` | Sprawdza typy TypeScript bez generowania plików. |
| `npm run build` | Sprawdza typy i tworzy wersję produkcyjną w `dist/`. |
| `npm run preview` | Uruchamia lokalny podgląd wcześniej zbudowanej wersji. |

Przy ręcznej weryfikacji zaloguj się [kontem demonstracyjnym](../foundly-backend/README.md#konto-demonstracyjne), dodaj zgłoszenie i otwórz jego szczegóły z listy. Sprawdź podpowiedzi po wysłaniu pustego formularza, a następnie edytuj zapisane zgłoszenie. Otwórz edycję w dwóch kartach: po zapisie w pierwszej starszy zapis w drugiej powinien zostać odrzucony, a wpisane wartości powinny pozostać w formularzu.

## Widoki i zapis

| Adres | Działanie |
| --- | --- |
| `/` | Lista i filtry ogłoszeń pobieranych wyłącznie z API. Przy pustych wynikach pojawia się komunikat o braku ogłoszeń. |
| `/moje-ogloszenia` | Ogłoszenia zalogowanego użytkownika, domyślnie aktywne i zakończone, z filtrami i stronicowaniem. Autor może zakończyć aktywne zgłoszenie lub usunąć własne zgłoszenie po potwierdzeniu. Wejście przez „Twoje konto” w nagłówku. |
| `/ogloszenia/:id` | Publiczny odczyt szczegółów. Przycisk „Edytuj zgłoszenie” widzi autor aktywnego zgłoszenia. |
| `/ogloszenia/nowe` | Dodawanie zgłoszenia po zalogowaniu. |
| `/ogloszenia/:id/edytuj` | Edycja aktywnego zgłoszenia przez autora. Typ zgłoszenia pozostaje bez zmian. |

Formularze korzystają ze wspólnego komponentu i pobierają kategorie z API. Wymagają nazwy, kategorii, opisu, daty i miejsca zdarzenia. Kolor jest opcjonalny. Data nie może być przyszła według strefy `Europe/Warsaw`. Filtr „Lokalizacja” na liście pozwala wybrać miasto z podpowiedzi i szuka ogłoszeń według odległości od wybranego punktu.

### Miejsce zdarzenia

W filtrach listy pole „Lokalizacja” podpowiada polskie miasta z lokalnego wykazu, także bez polskich znaków. Miasta o tej samej nazwie są rozróżniane województwem. Wybór podpowiedzi ustawia środek i otwiera mapę z domyślnym promieniem 5 km. Sekcja „Mapa i promień wyszukiwania” pozwala doprecyzować środek kliknięciem lub przeciągnięciem znacznika i ustawić promień od 0,5 do 30 km. Nie trzeba wybierać miasta, aby wskazać punkt na mapie. Zmiana wpisywanej nazwy usuwa poprzedni punkt; przed wyszukiwaniem trzeba wybrać podpowiedź albo wskazać punkt. Wyszukiwanie ulic i pełnych adresów nie jest obsługiwane. Okrąg pokazuje zasięg. „Pokaż ogłoszenia” stosuje obszar razem z pozostałymi filtrami, a wyniki są sortowane od najbliższych. „Usuń obszar” wyłącza ograniczenie przestrzenne przy następnym zastosowaniu filtrów, a „Wyczyść” od razu resetuje wszystkie filtry. Frontend przekazuje `latitude`, `longitude` i `radiusMeters` do istniejącego API wyszukiwania w promieniu. Nazwa wybranego miasta służy do prezentacji; frontend nie przesyła parametru tekstowego `location`, więc nie zawęża dodatkowo wyników według etykiety miejsca.

Wpisz nazwę miasta, np. **Częstochowa**. Po przecinku możesz dopisać dzielnicę lub ulicę, np. **Częstochowa, Raków**. Przy powtarzającej się nazwie miasta formularz poprosi o województwo, np. **Olsztyn, śląskie**.

Pole „Miejsce zdarzenia” w formularzu tworzenia i edycji korzysta z tych samych podpowiedzi miast co filtry, z obsługą polskich znaków i rozróżnianiem województw. Wybór miasta ustawia nazwę miejsca i znacznik na mapie; wybranie kolejnego miasta zastępuje poprzedni punkt. Mapa Leaflet z podkładem OpenStreetMap pokazuje orientacyjny punkt wybranego miasta. Kliknięcie mapy lub przeciągnięcie znacznika pozwala wskazać dokładniejsze miejsce w formularzu tworzenia i edycji. Wybrany punkt pozostaje wybrany przy zmianie opisu miejsca; przycisk „Przywróć położenie z nazwy miejsca” cofa ręczny wybór. Nazwa miejsca jest nadal wymagana. Szczegóły ogłoszenia pokazują zapisany punkt wszystkim odwiedzającym. Podkład mapy wymaga połączenia z internetem, bez klucza API.

Frontend wysyła wpisany tekst oraz wybrany punkt, a bez ręcznego wyboru — orientacyjny punkt miasta z dołączonego wykazu. Wykaz pochodzi z warstwy urzędowych nazw miejscowości według stanu z 12 listopada 2026 r. Edycja pozostałych pól zachowuje dotychczasowy punkt. [Państwowy Rejestr Nazw Geograficznych GUGiK](https://www.geoportal.gov.pl/pl/dane/panstwowy-rejestr-nazw-geograficznych-prng/)

Po zapisie aplikacja przechodzi do szczegółów i potwierdza powodzenie. Brak zgłoszenia ma własny widok, a błędy zapisu pozostawiają dane w formularzu. Edycja przekazuje numer wersji otrzymany z API; przy konflikcie `409` trzeba wrócić do szczegółów i pobrać aktualne dane. [Format żądań dodawania i edycji](../foundly-backend/README.md#dodanie-i-edycja).

W „Moich ogłoszeniach” przycisk „Usuń zgłoszenie” jest dostępny dla autora zarówno przy aktywnych, jak i zakończonych zgłoszeniach. Usunięcie wymaga osobnego potwierdzenia i jest trwałe. Frontend wysyła `DELETE /api/reports/{id}?version=…` z aktualnym tokenem przez wspólną warstwę API. Po odpowiedzi `204` usuwa kartę, potwierdza operację i odświeża wyniki oraz liczniki; po opróżnieniu ostatniej strony wraca na ostatnią istniejącą stronę. Przy konflikcie wersji należy odświeżyć listę przed kolejną próbą. Błędy nie usuwają karty i nie powodują automatycznego ponowienia operacji.

## Struktura źródeł

```text
src/
├── components/
│   ├── AccountSession/      # Logowanie, rejestracja i profil
│   ├── ReportCard/          # Karta ogłoszenia
│   ├── ReportForm/          # Wspólny formularz dodawania i edycji
│   └── ReportPage/          # Ramka widoków i komunikaty pobierania
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
| `detailsTo` | `string` | Nie | Adres szczegółów. W kontekście routera wyświetla link „Zobacz szczegóły”. |
| `actions` | `ReactNode` | Nie | Dodatkowe akcje pod stopką karty, np. zakończenie lub usunięcie własnego zgłoszenia. |

`Report` zawiera pola `id`, `title`, `type`, `category`, `location`, `date`, `color` i `description` oraz opcjonalne `owner` i `recovered`. Data zdarzenia ma format `YYYY-MM-DD`; na karcie jest formatowana po polsku. Niepoprawna data wyświetla tekst „Brak daty”.

Typ `lost` oznacza zgubienie, a `found` — znalezienie przedmiotu. `recovered: true` wyświetla status „Zakończone”; w pozostałych przypadkach karta pokazuje „Aktywne ogłoszenie”.

Obsługiwane kategorie to: Elektronika, Klucze, Portfele i dokumenty, Torby i plecaki, Biżuteria oraz Inne. Dla nieznanej kategorii używana jest ikona paczki. Pola `color` i `owner` zachowano w modelu na potrzeby rozbudowy; nie zmieniają obecnie wyglądu karty.

## Logowanie i profil

Przycisk **Utwórz konto** obok **Zaloguj się** otwiera formularz rejestracji Keycloak przez `keycloak.register()`. Formularze mają [motyw Foundly](../docker/keycloak/themes/foundly/README.md), domyślnie po polsku, z możliwością zmiany na angielski. Po utworzeniu konta użytkownik wraca na stronę aplikacji; synchronizacja profilu działa tak samo jak po logowaniu. Motyw i rejestrację dla nowego lub istniejącego realmu ustawia `docker compose up -d keycloak-init` z głównego katalogu projektu.

Uruchom backend i Keycloak zgodnie z [instrukcją backendu](../foundly-backend/README.md), a frontend przez `npm run dev`. Otwórz `http://localhost:5173` i wybierz **Zaloguj się**. Konto demonstracyjne i jego hasło są opisane w instrukcji backendu; istniejące konto zachowuje dotychczasowe hasło.

Adapter `keycloak-js` używa Authorization Code z PKCE S256 i zakresów `openid profile email`. Po zalogowaniu lub przywróceniu sesji frontend wywołuje `PUT /api/me` bez body; ekran konta pokazuje nazwę i e-mail dopiero po poprawnej odpowiedzi. Synchronizacja jest ponawiana po odświeżeniu tokenu. Przy błędzie API można wybrać **Spróbuj ponownie**. Wylogowanie usuwa lokalny stan profilu, a spóźniona odpowiedź nie przywraca sesji. Tokeny są trzymane w pamięci adaptera, bez zapisywania w localStorage.

Domyślne adresy to backend `http://localhost:8080` i Keycloak `http://localhost:8081`, realm `foundly`. Można je zmienić w lokalnym pliku `.env.local` na podstawie [.env.example](.env.example): `VITE_API_URL`, `VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM`. Przy zmianie origin frontendu trzeba również zmienić callback i Web Origins klienta w Keycloak oraz CORS backendu. `npm run preview` używa innego portu, którego domyślna konfiguracja logowania nie obejmuje.

Implementacja sesji jest w `src/services/auth.ts`, jej testy w `src/services/auth.test.ts`, a widok konta w `src/components/AccountSession/`. Serwisy API pobierają aktualny token przez wspólny adapter przed chronionymi operacjami.

## Komunikacja z API

Komponenty i hooki korzystają z funkcji domenowych w `src/services/reports.ts`, np. `resolveReport(id, version, signal)`. Nie wykonują bezpośrednich wywołań `fetch` ani nie przekazują tokenów.

- `apiClient.ts` zawiera jedyne wywołanie `fetch`: wspólny adres `VITE_API_URL`, nagłówki, serializację JSON i odczyt odpowiedzi. Obsługuje również odpowiedzi bez treści (`204` i `205`). Domyślnie używa `cache: 'no-store'`.
- `authenticatedApi.ts` pobiera odświeżony token z `auth.ts`. Chronione operacje bez sesji kończą się `ApiError` ze statusem `401` przed wysłaniem żądania. Szczegóły zgłoszenia jawnie dopuszczają odczyt anonimowy; listy publiczne i kategorie nie wymagają inicjalizacji sesji.
- `auth.ts` zarządza sesją Keycloak. Synchronizacja profilu używa `apiClient.ts` z tokenem już posiadanym przez adapter, aby uniknąć cyklicznego oczekiwania podczas inicjalizacji.
- `reports.ts` określa endpointy, typy danych, parametry filtrów i wymagania autoryzacji.

Niepowodzenie HTTP zgłasza wspólny `ApiError` z `status` i komunikatem `detail` z odpowiedzi Problem Detail. Odpowiedzi błędów bez poprawnego JSON otrzymują komunikat zastępczy. Błędy sieciowe i anulowanie żądania pozostają odrębnymi błędami. Komponenty dobierają komunikaty i działania użytkownika do kontekstu; klient HTTP nie przekierowuje automatycznie do logowania ani nie ponawia operacji zapisu.

Nowe żądania przekazują dane przez opcję `json` i otrzymują `AbortSignal` od komponentu lub hooka. Dla odpowiedzi bez treści należy użyć typu `void`.

```ts
// W serwisie domenowym, dla nowej operacji wymagającej sesji:
return authenticatedRequest<ReportDetails>(`/api/reports/${encodeURIComponent(id)}/status`, {
  method: 'PATCH',
  json: { version, status: 'RESOLVED' },
  signal,
});
```
