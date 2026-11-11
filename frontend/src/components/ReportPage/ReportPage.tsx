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
