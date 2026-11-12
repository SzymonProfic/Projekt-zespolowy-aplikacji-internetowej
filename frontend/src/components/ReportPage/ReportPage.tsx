import type { ReactNode } from 'react';
import { ArrowLeft } from 'lucide-react';
import { Link } from 'react-router-dom';
import './ReportPage.css';

interface ReportPageProps {
  children: ReactNode;
  backTo?: string;
  backLabel?: string;
}

export function ReportPage({ children, backTo = '/', backLabel = 'Wróć do ogłoszeń' }: ReportPageProps) {
  return (
    <section className="report-page">
      <Link className="report-page__back" to={backTo}>
        <ArrowLeft size={16} aria-hidden="true" />
        {backLabel}
      </Link>
      {children}
    </section>
  );
}

export function ReportMessage({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="report-panel report-message">
      <h1>{title}</h1>
      {children}
    </div>
  );
}

export function ReportLoadMessage({ status, onRetry }: { status: 'loading' | 'not-found' | 'error'; onRetry: () => void }) {
  if (status === 'loading') {
    return (
      <ReportMessage title="Zgłoszenie">
        <p className="report-page__muted" role="status">Ładowanie zgłoszenia…</p>
      </ReportMessage>
    );
  }
  if (status === 'not-found') {
    return (
      <ReportMessage title="Nie znaleziono zgłoszenia">
        <p className="report-page__muted">Ogłoszenie mogło zostać usunięte lub adres jest niepoprawny.</p>
      </ReportMessage>
    );
  }
  return (
    <ReportMessage title="Nie udało się pobrać zgłoszenia">
      <p className="report-page__muted" role="alert">Sprawdź połączenie i spróbuj ponownie.</p>
      <button className="report-action" type="button" onClick={onRetry}>Spróbuj ponownie</button>
    </ReportMessage>
  );
}
