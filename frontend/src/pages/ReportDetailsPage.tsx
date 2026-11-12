import { useSyncExternalStore } from 'react';
import { Pencil, ShieldCheck } from 'lucide-react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { ReportLoadMessage, ReportPage } from '../components/ReportPage/ReportPage';
import { LocationMap } from '../components/LocationMap/LocationMap';
import { useReportDetails } from '../hooks/useReportDetails';
import { getAuthSnapshot, subscribeAuth } from '../services/auth';
import { canEditReport } from '../utils/reportForm';
import './ReportDetailsPage.css';

const dateFormatter = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'long', timeZone: 'UTC' });

export function ReportDetailsPage() {
  const { id = '' } = useParams();
  const { state, retry } = useReportDetails(id);
  const { state: navigationState } = useLocation();
  const { signedIn } = useSyncExternalStore(subscribeAuth, getAuthSnapshot);

  return (
    <ReportPage>
      {navigationState?.reportSaved === 'created' && (
        <p className="report-notice" role="status">Ogłoszenie zostało opublikowane.</p>
      )}
      {navigationState?.reportSaved === 'updated' && (
        <p className="report-notice" role="status">Zmiany w zgłoszeniu zostały zapisane.</p>
      )}
      {state.status !== 'found' && <ReportLoadMessage status={state.status} onRetry={retry} />}
      {state.status === 'found' && (
        <article className="report-panel" aria-labelledby="report-title">
          <span className={`report-details__badge report-details__badge--${state.report.type}`}>
            {state.report.type === 'lost' ? 'Zgubiono' : 'Znaleziono'}
          </span>
          <h1 id="report-title">{state.report.title}</h1>
          <p className="report-page__muted">
            {state.report.category} · {state.report.status === 'RESOLVED' ? 'Zakończone' : 'Aktywne ogłoszenie'}
          </p>
          <p className="report-details__description">{state.report.description}</p>
          <dl className="report-details__facts">
            <div><dt>Lokalizacja</dt><dd>{state.report.location}</dd></div>
            <div><dt>Data zdarzenia</dt><dd><time dateTime={state.report.date}>{dateFormatter.format(new Date(state.report.date))}</time></dd></div>
            <div><dt>Kolor</dt><dd>{state.report.color || 'Nie podano'}</dd></div>
          </dl>
          <section className="report-details__map" aria-labelledby="report-map-heading">
            <h2 id="report-map-heading">Miejsce zdarzenia na mapie</h2>
            {state.report.locationPoint || state.report.editableLocation ? (
              <LocationMap point={state.report.locationPoint ?? state.report.editableLocation} label={state.report.location} />
            ) : <p className="report-page__muted">Współrzędne tego miejsca nie są dostępne.</p>}
          </section>
          <p className="report-details__note">
            <ShieldCheck size={21} aria-hidden="true" />
            Dla bezpieczeństwa poproś o opis cech szczególnych. Ogłoszenie nie potwierdza własności.
          </p>
          {signedIn && canEditReport(state.report) && (
            <div className="report-details__actions">
              <Link className="report-action" to={`/ogloszenia/${state.report.id}/edytuj`}>
                <Pencil size={16} aria-hidden="true" /> Edytuj zgłoszenie
              </Link>
            </div>
          )}
        </article>
      )}
    </ReportPage>
  );
}
