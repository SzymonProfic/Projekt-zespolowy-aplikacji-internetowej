import { ApiError } from '../services/apiClient';
import { useEffect, useState } from 'react';
import { getReportById } from '../services/reports';
import type { ReportDetails } from '../types/report';

type DetailState =
  | { status: 'loading' }
  | { status: 'found'; report: ReportDetails }
  | { status: 'not-found' }
  | { status: 'error' };

const reportIdPattern = /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i;

export function useReportDetails(id: string) {
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<{ id: string; attempt: number; state: DetailState }>();
  const validId = reportIdPattern.test(id);

  useEffect(() => {
    if (!validId) return;
    const controller = new AbortController();

    async function load() {
      function finish(state: DetailState) {
        if (!controller.signal.aborted) setResult({ id, attempt, state });
      }

      try {
        const report = await getReportById(id, controller.signal);
        finish({ status: 'found', report });
      } catch (error) {
        finish({ status: error instanceof ApiError && error.status === 404 ? 'not-found' : 'error' });
      }
    }

    void load();
    return () => controller.abort();
  }, [id, attempt, validId]);

  const state: DetailState = !validId ? { status: 'not-found' }
    : result?.id === id && result.attempt === attempt ? result.state
    : { status: 'loading' };
  return { state, retry: () => setAttempt((current) => current + 1) };
}
