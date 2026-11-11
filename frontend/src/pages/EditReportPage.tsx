import { useSyncExternalStore } from 'react';
import { useParams } from 'react-router-dom';
import { ReportForm } from '../components/ReportForm/ReportForm';
import { ReportLoadMessage, ReportMessage, ReportPage } from '../components/ReportPage/ReportPage';
import { useReportDetails } from '../hooks/useReportDetails';
import { getAuthSnapshot, login, subscribeAuth } from '../services/auth';
import { canEditReport, reportToFormValues } from '../utils/reportForm';

export function EditReportPage() {
  const { id = '' } = useParams();
  const { state, retry } = useReportDetails(id);
  const { signedIn } = useSyncExternalStore(subscribeAuth, getAuthSnapshot);

  if (state.status !== 'found') {
    return <ReportPage><ReportLoadMessage status={state.status} onRetry={retry} /></ReportPage>;
  }

  const { report } = state;
  return (
    <ReportPage backTo={`/ogloszenia/${report.id}`} backLabel="Wróć do szczegółów">
      {canEditReport(report) ? (
        <ReportForm key={report.id} editing={{
          id: report.id,
          version: report.version,
          initialValues: reportToFormValues(report),
        }} />
      ) : (
        <ReportMessage title="Nie możesz edytować tego zgłoszenia">
          <p className="report-page__muted">
            {report.owner && report.status === 'RESOLVED'
              ? 'Zakończonego zgłoszenia nie można edytować.'
              : signedIn ? 'Edycja jest dostępna dla autora aktywnego zgłoszenia.' : 'Zaloguj się, aby edytować własne zgłoszenie.'}
          </p>
          {!signedIn && <button className="report-action" type="button" onClick={() => void login()}>Zaloguj się</button>}
        </ReportMessage>
      )}
    </ReportPage>
  );
}
