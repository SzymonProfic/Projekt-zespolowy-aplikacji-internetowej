import { ApiError } from '../../services/apiClient';
import { useEffect, useId, useRef, useState } from 'react';
import { login } from '../../services/auth';
import { deleteReport, resolveReport } from '../../services/reports';
import type { ReportDetails } from '../../types/report';
import './ReportActions.css';

interface Props {
  report: ReportDetails;
  onResolved: (report: ReportDetails) => void;
  onDeleted: () => void;
  onRefresh: () => void;
}

export function ReportActions({ report, onResolved, onDeleted, onRefresh }: Props) {
  const descriptionId = useId();
  const [action, setAction] = useState<'resolve' | 'delete' | null>(null);
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<{ message: string; status?: number } | null>(null);
  const request = useRef<AbortController | null>(null);
  useEffect(() => () => request.current?.abort(), []);

  if (!report.owner) return null;

  async function confirmAction() {
    if (request.current || !action) return;
    const controller = new AbortController();
    request.current = controller;
    setSaving(true);
    setFailure(null);
    try {
      if (action === 'delete') {
        await deleteReport(report.id, report.version, controller.signal);
        if (!controller.signal.aborted) onDeleted();
      } else {
        const updated = await resolveReport(report.id, report.version, controller.signal);
        if (!controller.signal.aborted) onResolved(updated);
      }
    } catch (error: unknown) {
      if (controller.signal.aborted) return;
      const status = error instanceof ApiError ? error.status : undefined;
      const message = status === 401 ? 'Sesja wygasła. Zaloguj się ponownie.'
        : status === 403 ? 'Nie masz uprawnień do wykonania tej operacji.'
        : status === 404 ? 'To zgłoszenie już nie istnieje. Odśwież listę.'
        : status === 409 ? 'Zgłoszenie zostało zmienione. Odśwież listę i sprawdź jego aktualne dane.'
        : action === 'delete' ? 'Nie udało się usunąć zgłoszenia. Sprawdź połączenie i spróbuj ponownie.'
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
    <div className="report-actions" role="group" aria-label={`Zarządzanie zgłoszeniem: ${report.title}`} aria-busy={saving}>
      {action ? (
        <>
          <p id={descriptionId}>{action === 'delete'
            ? `Usunąć zgłoszenie „${report.title}”? Zostanie trwale usunięte. Tej operacji nie można cofnąć.`
            : 'Zakończyć to zgłoszenie? Zniknie z listy aktywnych ogłoszeń. Nie będzie można go edytować ani ponownie otworzyć.'}</p>
          <div className="report-actions__buttons">
            <button className={`report-card__select ${action === 'delete' ? 'report-actions__delete-confirm' : 'report-actions__confirm'}`} type="button"
              disabled={saving || failure?.status === 401 || needsRefresh}
              aria-describedby={descriptionId} onClick={() => void confirmAction()}>
              {saving ? (action === 'delete' ? 'Usuwanie…' : 'Zapisywanie…')
                : action === 'delete' ? 'Potwierdź usunięcie' : 'Potwierdź zakończenie'}
            </button>
            <button className="report-card__select" type="button" disabled={saving}
              onClick={() => { setAction(null); setFailure(null); }}>Anuluj</button>
          </div>
        </>
      ) : (
        <div className="report-actions__buttons">
          {report.status === 'ACTIVE' && <button className="report-card__select" type="button" onClick={() => setAction('resolve')}
            aria-label={`Zakończ zgłoszenie: ${report.title}`}>Zakończ zgłoszenie</button>}
          <button className="report-card__select report-actions__delete" type="button" onClick={() => setAction('delete')}
            aria-label={`Usuń zgłoszenie: ${report.title}`}>Usuń zgłoszenie</button>
        </div>
      )}
      {failure && <div role="alert">
        <p>{failure.message}</p>
        {failure.status === 401 && <button className="report-card__select" type="button" onClick={() => void login()}>Zaloguj się</button>}
        {needsRefresh && <button className="report-card__select" type="button" onClick={onRefresh}>Odśwież listę</button>}
      </div>}
    </div>
  );
}
