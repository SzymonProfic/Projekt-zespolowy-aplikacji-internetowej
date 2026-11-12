import { useSyncExternalStore } from 'react';
import { ReportMessage, ReportPage } from '../components/ReportPage/ReportPage';
import { getAuthSnapshot, login, retryProfile, subscribeAuth } from '../services/auth';
import { ReportListPage } from './ReportListPage';

export function MyReportsPage() {
  const session = useSyncExternalStore(subscribeAuth, getAuthSnapshot);
  if (session.signedIn) return <ReportListPage mine />;

  return (
    <ReportPage>
      <ReportMessage title="Moje ogłoszenia">
        {session.status === 'loading' ? (
          <p className="report-page__muted" role="status">Sprawdzanie sesji…</p>
        ) : (
          <>
            <p className="report-page__muted">Zaloguj się, aby zobaczyć swoje ogłoszenia.</p>
            {session.error && <p className="report-page__muted" role="alert">{session.error}</p>}
            <button className="report-action report-action--primary" type="button"
              onClick={() => void (session.status === 'error' ? retryProfile() : login())}>
              {session.status === 'error' ? 'Spróbuj ponownie' : 'Zaloguj się'}
            </button>
          </>
        )}
      </ReportMessage>
    </ReportPage>
  );
}
