import { useEffect, useState } from 'react';
import { Plus } from 'lucide-react';
import { Link } from 'react-router-dom';
import { ReportCard } from '../components/ReportCard/ReportCard';
import { LocationMap } from '../components/LocationMap/LocationMap';
import { CitySearch } from '../components/CitySearch/CitySearch';
import { demoReports } from '../demo/reports';
import { getCategories, getMyReports, getReports, ReportRequestError, type ReportFilters } from '../services/reports';
import { getAccessToken, login } from '../services/auth';
import type { Category, Report } from '../types/report';

const emptyFilters: ReportFilters = { type: '', status: 'ACTIVE', categoryCode: '', centerLabel: '', center: null, radiusKm: 5 };
const radiusFormatter = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 1 });

export function ReportListPage({ mine = false }: { mine?: boolean }) {
  const defaultFilters: ReportFilters = mine ? { ...emptyFilters, status: 'ALL' } : emptyFilters;
  const [filters, setFilters] = useState<ReportFilters>(defaultFilters);
  const [appliedFilters, setAppliedFilters] = useState<ReportFilters>(defaultFilters);
  const [locationQuery, setLocationQuery] = useState('');
  const [locationError, setLocationError] = useState('');
  const [mapOpen, setMapOpen] = useState(false);
  const [categories, setCategories] = useState<Category[]>([]);
  const [reports, setReports] = useState<Report[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [unauthorized, setUnauthorized] = useState(false);
  const [attempt, setAttempt] = useState(0);

  const displayedReports = !mine && reports.length === 0 && totalElements === 0 && !appliedFilters.center
    ? demoReports.filter((report) => {
      const statusMatches = appliedFilters.status === 'ALL' || (appliedFilters.status === 'RESOLVED' ? report.recovered : !report.recovered);
      const typeMatches = !appliedFilters.type || report.type === appliedFilters.type;
      const categoryMatches = !appliedFilters.categoryCode || categories.find((category) => category.code === appliedFilters.categoryCode)?.name === report.category;
      return statusMatches && typeMatches && categoryMatches;
    })
    : reports;
  const showingDemoReports = displayedReports !== reports;

  useEffect(() => {
    getCategories().then(setCategories).catch(() => setError('Nie udało się pobrać kategorii.'));
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError('');
    setUnauthorized(false);
    async function load() {
      if (!mine) return getReports(appliedFilters, page, controller.signal);
      const token = await getAccessToken();
      if (controller.signal.aborted) return null;
      if (!token) throw new ReportRequestError(401, 'Brak aktywnej sesji');
      return getMyReports(appliedFilters, page, token, controller.signal);
    }
    void load()
      .then((result) => {
        if (controller.signal.aborted || !result) return;
        setReports(result.content);
        setTotalPages(result.totalPages);
        setTotalElements(result.totalElements);
      })
      .catch((failure: unknown) => {
        if (controller.signal.aborted) return;
        const expired = mine && failure instanceof ReportRequestError && failure.status === 401;
        setUnauthorized(expired);
        setReports([]);
        setError(expired ? 'Sesja wygasła. Zaloguj się ponownie, aby zobaczyć swoje ogłoszenia.'
          : 'Nie udało się pobrać ogłoszeń. Sprawdź połączenie z serwerem i spróbuj ponownie.');
      })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [appliedFilters, page, mine, attempt]);

  function changeFilter<K extends keyof ReportFilters>(key: K, value: ReportFilters[K]) {
    setFilters((current) => ({ ...current, [key]: value }));
  }

  function applyFilters(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (locationQuery.trim() && !filters.center) {
      setLocationError('Wybierz miasto z podpowiedzi lub wskaż punkt na mapie.');
      return;
    }
    setLocationError('');
    setError('');
    setPage(0);
    setAppliedFilters({ ...filters });
  }

  function clearFilters() {
    setFilters(defaultFilters);
    setLocationQuery('');
    setLocationError('');
    setPage(0);
    setError('');
    setAppliedFilters(defaultFilters);
  }

  return (
    <>
      <header className="foundly-heading">
        {mine && <Link className="report-page__back" to="/">Wróć do wszystkich ogłoszeń</Link>}
        <p className="foundly-eyebrow">{mine ? 'TWOJE KONTO' : 'ZGUBIONE I ZNALEZIONE'}</p>
        <h1>{mine ? 'Moje ogłoszenia' : 'Pomóż przedmiotom wrócić do domu.'}</h1>
        <p>{mine ? 'Przeglądaj swoje zgłoszenia i otwieraj ich szczegóły, aby wprowadzić zmiany.' : 'Przeszukaj zgłoszenia i znajdź przedmiot, którego szukasz.'}</p>
        <Link className="report-action report-action--primary foundly-heading__action" to="/ogloszenia/nowe">
          <Plus size={17} aria-hidden="true" /> Dodaj zgłoszenie
        </Link>
      </header>

      <form className="filters" onSubmit={applyFilters}>
        <details className="filters__details" open>
          <summary className="filters__summary"><span>Filtry</span><span className="filters__summary-note">Rozwiń, aby zmienić kryteria</span></summary>
          <div className="filters__content">
          <div className="filters__heading"><div><h2>Wybierz kryteria</h2></div><button className="filters__clear" type="button" onClick={clearFilters}>Wyczyść</button></div>
          <div className="filters__grid">
          <label className="filter-field"><span>Rodzaj zgłoszenia</span><select value={filters.type} onChange={(event) => changeFilter('type', event.target.value as ReportFilters['type'])}><option value="">Wszystkie</option><option value="lost">Zgubione</option><option value="found">Znalezione</option></select></label>
          <label className="filter-field"><span>Status</span><select value={filters.status} onChange={(event) => changeFilter('status', event.target.value as ReportFilters['status'])}><option value="ACTIVE">Aktywne</option><option value="RESOLVED">Zakończone</option><option value="ALL">Wszystkie</option></select></label>
          <label className="filter-field"><span>Kategoria</span><select value={filters.categoryCode} onChange={(event) => changeFilter('categoryCode', event.target.value)}><option value="">Wszystkie kategorie</option>{categories.map((category) => <option key={category.code} value={category.code}>{category.name}</option>)}</select></label>
          <CitySearch value={locationQuery} error={locationError}
            onChange={(query) => {
              setLocationQuery(query);
              setLocationError('');
              setFilters((current) => ({ ...current, center: null, centerLabel: '' }));
            }}
            onSelect={(city) => {
              setLocationQuery(city.label);
              setLocationError('');
              setFilters((current) => ({ ...current, center: city.point, centerLabel: city.label }));
              setMapOpen(true);
            }} />
          </div>
          <details className="filters__spatial" open={mapOpen} onToggle={(event) => setMapOpen(event.currentTarget.open)}>
            <summary>Mapa i promień wyszukiwania{filters.center ? ` · ${radiusFormatter.format(filters.radiusKm)} km` : ''}</summary>
            <p className="filters__map-help">
              Wybierz miasto z podpowiedzi lub kliknij mapę, aby ustawić środek wyszukiwania.
              Możesz doprecyzować miejsce, przeciągając znacznik.
              Ustaw promień i wybierz „Pokaż ogłoszenia”. Wyniki będą uporządkowane od najbliższych.
              Pozostałe filtry również obowiązują.
            </p>
            <div className="filters__radius">
              <label className="filter-field" htmlFor="search-radius">
                <span>Promień wyszukiwania: {radiusFormatter.format(filters.radiusKm)} km</span>
                <input id="search-radius" type="range" min="0.5" max="30" step="0.5" value={filters.radiusKm}
                  aria-valuetext={`${radiusFormatter.format(filters.radiusKm)} km`}
                  onChange={(event) => changeFilter('radiusKm', Number(event.target.value))} />
              </label>
              <button className="filters__clear" type="button" disabled={!filters.center}
                onClick={() => {
                  setFilters((current) => ({ ...current, center: null, centerLabel: '' }));
                  setLocationQuery('');
                  setLocationError('');
                }}>Usuń obszar</button>
            </div>
            <LocationMap point={filters.center} label="Środek wyszukiwania"
              radiusMeters={filters.radiusKm * 1000} onChange={(point) => {
                setFilters((current) => ({ ...current, center: point, centerLabel: 'Punkt na mapie' }));
                setLocationQuery('');
                setLocationError('');
              }} />
            <p className="filters__map-status" role="status">
              {filters.center
                ? `${filters.centerLabel}: ${filters.center.latitude.toFixed(5)}, ${filters.center.longitude.toFixed(5)}. Promień: ${radiusFormatter.format(filters.radiusKm)} km.`
                : 'Nie wybrano środka — wyszukiwanie obejmuje wszystkie lokalizacje.'}
            </p>
          </details>
          </div>
        </details>
        <div className="filters__actions"><button className="filters__submit" type="submit">Pokaż ogłoszenia</button></div>
      </form>

      <section className="results" aria-live="polite">
        <div className="results__heading"><h2>Ogłoszenia</h2>{!loading && !error && <span>{showingDemoReports ? `${displayedReports.length} przykładowe` : `${totalElements} ${totalElements === 1 ? 'wynik' : totalElements >= 2 && totalElements <= 4 ? 'wyniki' : 'wyników'}`}</span>}</div>
        {appliedFilters.center && <p className="results__area">Obszar wyszukiwania: {appliedFilters.centerLabel}, w promieniu {radiusFormatter.format(appliedFilters.radiusKm)} km. Najbliższe ogłoszenia najpierw.</p>}
        {error && <div className="results__message results__message--error" role="alert">
          <p>{error}</p>
          <button className="report-action" type="button" onClick={() => {
            if (unauthorized) void login();
            else setAttempt((value) => value + 1);
          }}>{unauthorized ? 'Zaloguj się' : 'Spróbuj ponownie'}</button>
        </div>}
        {loading && <div className="results__message">Ładowanie ogłoszeń…</div>}
        {!loading && !error && showingDemoReports && <p className="results__demo-note">To przykładowe zgłoszenia do podglądu. Rzeczywiste ogłoszenia pojawią się po dodaniu ich w aplikacji.</p>}
        {!loading && !error && displayedReports.length === 0 && <div className="results__message">{mine ? 'Nie masz ogłoszeń spełniających wybrane kryteria. Dodaj zgłoszenie lub zmień filtry.' : 'Nie znaleziono ogłoszeń spełniających wybrane kryteria.'}</div>}
        {!loading && !error && displayedReports.length > 0 && (
          <div className="results__grid">
            {displayedReports.map((report) => (
              <ReportCard
                key={report.id}
                report={report}
                detailsTo={showingDemoReports ? undefined : `/ogloszenia/${report.id}`}
              />
            ))}
          </div>
        )}
        {!loading && !error && !showingDemoReports && totalPages > 1 && <nav className="pagination" aria-label="Stronicowanie wyników"><button type="button" disabled={page === 0} onClick={() => setPage((value) => value - 1)}>Poprzednia</button><span>Strona {page + 1} z {totalPages}</span><button type="button" disabled={page + 1 >= totalPages} onClick={() => setPage((value) => value + 1)}>Następna</button></nav>}
      </section>
    </>
  );
}
