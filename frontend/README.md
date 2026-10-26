**Foundly — karta ogłoszenia**

Pierwszy etap frontendu: komponent `ReportCard` z własnymi stylami oraz mały podgląd trzech przykładowych kart. Wykorzystuje React, TypeScript, Vite i ikony Lucide. Wymagany Node.js 22.12+ z gałęzi 22 lub Node.js 24+.

Z tego katalogu wykonaj:

```bash
npm ci
npm run dev
```

Otwórz adres wypisany w terminalu. Kompilacja wraz ze sprawdzeniem typów:

```bash
npm run build
```

Samo sprawdzenie typów: `npm run typecheck`.

Komponent znajduje się w `src/components/ReportCard/ReportCard.tsx`, a jego style w sąsiednim `ReportCard.css`. Typ danych `Report` znajduje się w `src/types/report.ts` i zachowuje strukturę modelu z prototypu aplikacji.

Przykład użycia wewnątrz aplikacji React:

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

Właściwość `report` jest wymagana. Opcjonalna funkcja `onSelect` otrzymuje wybrane ogłoszenie i włącza przycisk „Wybierz”:

```tsx
<ReportCard report={report} onSelect={(selected) => console.log(selected.id)} />
```

Ikona i kolor ilustracji zależą od kategorii. Nieznana kategoria otrzymuje ikonę paczki. Typ `lost` wyświetla etykietę „Zgubiono”, a `found` — „Znaleziono”. `recovered: true` zmienia status na „Zakończone”. Data jest wyświetlana po polsku; brak poprawnej daty pokazuje „Brak daty”.

Komponent korzysta z własnego CSS i nie wymaga Tailwinda, React Routera ani konfiguracji Figmy. Plik `App.tsx`, katalog `demo/` i `src/index.css` służą wyłącznie do prezentacji komponentu; przy rozbudowie aplikacji można zastąpić ten podgląd właściwym widokiem.

W tym etapie dostępne są karty i obsługa ich wyboru. Formularze, wyszukiwarka, logowanie, routing, widok szczegółów i integracja z API pozostają kolejnymi zadaniami. Przykładowe dane znajdują się lokalnie w `src/demo/reports.ts`.
