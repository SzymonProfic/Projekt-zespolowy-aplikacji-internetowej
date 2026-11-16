import { ApiError } from '../../services/apiClient';
import { useEffect, useId, useRef, useState } from 'react';
import { login } from '../../services/auth';
import { resolveReport } from '../../services/reports';
import type { ReportDetails } from '../../types/report';
import './ReportStatusActions.css';

interface Props {
  report: ReportDetails;
  onResolved: (report: ReportDetails) => void;
  onRefresh: () => void;
}

export function ReportStatusActions({ report, onResolved, onRefresh }: Props) {
  const descriptionId = useId();
  const [confirming, setConfirming] = useState(false);
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<{ message: string; status?: number } | null>(null);
  const request = useRef<AbortController | null>(null);
  useEffect(() => () => request.current?.abort(), []);

  if (!report.owner || report.status !== 'ACTIVE') return null;

  async function finishReport() {
    if (request.current) return;
    const controller = new AbortController();
    request.current = controller;
    setSaving(true);
    setFailure(null);
    try {
      const updated = await resolveReport(report.id, report.version, controller.signal);
      if (!controller.signal.aborted) onResolved(updated);
    } catch (error: unknown) {
      if (controller.signal.aborted) return;
      const status = error instanceof ApiError ? error.status : undefined;
      const message = status === 401 ? 'Sesja wygasła. Zaloguj się ponownie.'
        : status === 403 ? 'Nie masz uprawnień do zakończenia tego zgłoszenia.'
        : status === 404 ? 'To zgłoszenie już nie istnieje. Odśwież listę.'
        : status === 409 ? 'Zgłoszenie zostało zmienione. Odśwież listę i sprawdź jego aktualny status.'
        : 'Nie udało się zakończyć zgłoszenia. Sprawdź połączenie i spróbuj ponownie.';
      setFailure({ message, status });
    } finally {
      if (!controller.signal.aborted) {
        request.current = null;
        setSaving(false);
      }
    }
  }

  const needsRefresh = failure?.status === 403 || failure?.status === 404 || failure?.status === 409;
  return (
    <div className="report-status-actions" role="group" aria-label={`Zarządzanie statusem: ${report.title}`} aria-busy={saving}>
      {confirming ? (
        <>
          <p id={descriptionId}>Zakończyć to zgłoszenie? Zniknie z listy aktywnych ogłoszeń. Nie będzie można go edytować ani ponownie otworzyć.</p>
          <div className="report-status-actions__buttons">
            <button className="report-card__select report-status-actions__confirm" type="button"
              disabled={saving || failure?.status === 401 || needsRefresh}
              aria-describedby={descriptionId} onClick={() => void finishReport()}>
              {saving ? 'Zapisywanie…' : 'Potwierdź zakończenie'}
            </button>
            <button className="report-card__select" type="button" disabled={saving}
              onClick={() => { setConfirming(false); setFailure(null); }}>Anuluj</button>
          </div>
        </>
      ) : (
        <button className="report-card__select" type="button" onClick={() => setConfirming(true)}
          aria-label={`Zakończ zgłoszenie: ${report.title}`}>Zakończ zgłoszenie</button>
      )}
      {failure && <div role="alert">
        <p>{failure.message}</p>
        {failure.status === 401 && <button className="report-card__select" type="button" onClick={() => void login()}>Zaloguj się</button>}
        {needsRefresh && <button className="report-card__select" type="button" onClick={onRefresh}>Odśwież listę</button>}
      </div>}
    </div>
  );
}
