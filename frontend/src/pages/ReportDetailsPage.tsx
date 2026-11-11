import { ShieldCheck } from 'lucide-react';
import { useParams } from 'react-router-dom';
import { ReportMessage, ReportPage } from '../components/ReportPage/ReportPage';
import { useReportDetails } from '../hooks/useReportDetails';
import './ReportDetailsPage.css';

const dateFormatter = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'long', timeZone: 'UTC' });

export function ReportDetailsPage() {
  const { id = '' } = useParams();
  const { state, retry } = useReportDetails(id);

  return (
    <ReportPage>
      {state.status === 'loading' && (
        <ReportMessage title="Szczegóły zgłoszenia">
          <p className="report-page__muted" role="status">Ładowanie zgłoszenia…</p>
        </ReportMessage>
      )}
      {state.status === 'not-found' && (
        <ReportMessage title="Nie znaleziono zgłoszenia">
          <p className="report-page__muted">Ogłoszenie mogło zostać usunięte lub adres jest niepoprawny.</p>
        </ReportMessage>
      )}
      {state.status === 'error' && (
        <ReportMessage title="Nie udało się pobrać zgłoszenia">
          <p className="report-page__muted" role="alert">Sprawdź połączenie i spróbuj ponownie.</p>
          <button className="report-action" type="button" onClick={retry}>Spróbuj ponownie</button>
        </ReportMessage>
      )}
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
          <p className="report-details__note">
            <ShieldCheck size={21} aria-hidden="true" />
            Dla bezpieczeństwa poproś o opis cech szczególnych. Ogłoszenie nie potwierdza własności.
          </p>
        </article>
      )}
    </ReportPage>
  );
}
