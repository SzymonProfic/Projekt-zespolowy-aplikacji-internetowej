import { useEffect, useState } from 'react';
import { Plus } from 'lucide-react';
import { Link } from 'react-router-dom';
import { ReportCard } from '../components/ReportCard/ReportCard';
import { demoReports } from '../demo/reports';
import { getCategories, getReports, type ReportFilters } from '../services/reports';
import type { Category, Report } from '../types/report';

const emptyFilters: ReportFilters = { type: '', status: 'ACTIVE', categoryCode: '', location: '' };

export function ReportListPage() {
  const [filters, setFilters] = useState<ReportFilters>(emptyFilters);
  const [appliedFilters, setAppliedFilters] = useState<ReportFilters>(emptyFilters);
  const [categories, setCategories] = useState<Category[]>([]);
  const [reports, setReports] = useState<Report[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const displayedReports = reports.length === 0 && totalElements === 0 && !appliedFilters.location
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
    let active = true;
    setLoading(true);
    setError('');
    getReports(appliedFilters, page)
      .then((result) => {
        if (!active) return;
        setReports(result.content);
        setTotalPages(result.totalPages);
        setTotalElements(result.totalElements);
      })
      .catch(() => { if (active) setError('Nie udało się pobrać ogłoszeń. Sprawdź połączenie z serwerem i spróbuj ponownie.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [appliedFilters, page]);

  function changeFilter<K extends keyof ReportFilters>(key: K, value: ReportFilters[K]) {
    setFilters((current) => ({ ...current, [key]: value }));
  }

  function applyFilters(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setPage(0);
    setAppliedFilters({ ...filters, location: filters.location.trim() });
  }

  function clearFilters() {
    setFilters(emptyFilters);
    setPage(0);
    setError('');
    setAppliedFilters(emptyFilters);
  }

  return (
    <>
      <header className="foundly-heading">
        <p className="foundly-eyebrow">ZGUBIONE I ZNALEZIONE</p>
        <h1>Pomóż przedmiotom wrócić do domu.</h1>
        <p>Przeszukaj zgłoszenia i znajdź przedmiot, którego szukasz.</p>
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
          <label className="filter-field">
            <span>Lokalizacja</span>
            <input value={filters.location} maxLength={255} placeholder="np. Warszawa, Mokotów"
              onChange={(event) => changeFilter('location', event.target.value)} />
          </label>
          </div>
          </div>
        </details>
        <div className="filters__actions"><button className="filters__submit" type="submit">Pokaż ogłoszenia</button></div>
      </form>

      <section className="results" aria-live="polite">
        <div className="results__heading"><h2>Ogłoszenia</h2>{!loading && !error && <span>{showingDemoReports ? `${displayedReports.length} przykładowe` : `${totalElements} ${totalElements === 1 ? 'wynik' : totalElements >= 2 && totalElements <= 4 ? 'wyniki' : 'wyników'}`}</span>}</div>
        {error && <div className="results__message results__message--error" role="alert">{error}</div>}
        {loading && <div className="results__message">Ładowanie ogłoszeń…</div>}
        {!loading && !error && showingDemoReports && <p className="results__demo-note">To przykładowe zgłoszenia do podglądu. Rzeczywiste ogłoszenia pojawią się po dodaniu ich w aplikacji.</p>}
        {!loading && !error && displayedReports.length === 0 && <div className="results__message">Nie znaleziono ogłoszeń spełniających wybrane kryteria.</div>}
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
