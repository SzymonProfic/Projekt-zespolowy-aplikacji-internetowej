import { useId } from 'react';
import {
  ArrowUpRight,
  Backpack,
  CalendarDays,
  Gem,
  Glasses,
  Headphones,
  KeyRound,
  MapPin,
  Package,
  Wallet,
  type LucideIcon,
} from 'lucide-react';
import { Link } from 'react-router-dom';
import type { Report } from '../../types/report';
import './ReportCard.css';

interface ReportCardProps {
  report: Report;
  onSelect?: (report: Report) => void;
  detailsTo?: string;
}

const categoryAppearance = new Map<string, { icon: LucideIcon; variant: string }>([
  ['Elektronika', { icon: Headphones, variant: 'electronics' }],
  ['Klucze', { icon: KeyRound, variant: 'keys' }],
  ['Portfele i dokumenty', { icon: Wallet, variant: 'wallets' }],
  ['Torby i plecaki', { icon: Backpack, variant: 'bags' }],
  ['Biżuteria', { icon: Gem, variant: 'jewellery' }],
  ['Inne', { icon: Glasses, variant: 'other' }],
]);

const dateFormatter = new Intl.DateTimeFormat('pl-PL', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  timeZone: 'UTC',
});

export function ReportCard({ report, onSelect, detailsTo }: ReportCardProps) {
  const titleId = useId();
  const { icon: Icon, variant } = categoryAppearance.get(report.category) ?? {
    icon: Package,
    variant: 'other',
  };
  const date = new Date(report.date);
  const hasValidDate = !Number.isNaN(date.getTime());

  return (
    <article className="report-card" aria-labelledby={titleId}>
      <div className={`report-card__art report-card__art--${variant}`}>
        <Icon className="report-card__illustration" strokeWidth={1.25} aria-hidden="true" />
        <span className={`report-card__badge report-card__badge--${report.type}`}>
          {report.type === 'lost' ? 'Zgubiono' : 'Znaleziono'}
        </span>
        <span className="report-card__category">{report.category}</span>
      </div>

      <div className="report-card__body">
        <h2 className="report-card__title" id={titleId}>{report.title}</h2>
        <p className="report-card__description">{report.description}</p>

        <div className="report-card__meta">
          <span>
            <MapPin size={14} aria-hidden="true" />
            {report.location}
          </span>
          <span>
            <CalendarDays size={14} aria-hidden="true" />
            <time dateTime={hasValidDate ? report.date : undefined}>
              {hasValidDate ? dateFormatter.format(date) : 'Brak daty'}
            </time>
          </span>
        </div>

        <div className="report-card__footer">
          <span className={`report-card__status${report.recovered ? ' report-card__status--closed' : ''}`}>
            <span className="report-card__status-dot" aria-hidden="true" />
            {report.recovered ? 'Zakończone' : 'Aktywne ogłoszenie'}
          </span>
          {detailsTo ? (
            <Link className="report-card__select" to={detailsTo} aria-label={`Zobacz szczegóły: ${report.title}`}>
              Zobacz szczegóły
              <ArrowUpRight size={16} aria-hidden="true" />
            </Link>
          ) : onSelect && (
            <button
              className="report-card__select"
              type="button"
              onClick={() => onSelect(report)}
              aria-label={`Wybierz ogłoszenie: ${report.title}`}
            >
              Wybierz
              <ArrowUpRight size={16} aria-hidden="true" />
            </button>
          )}
        </div>
      </div>
    </article>
  );
}
