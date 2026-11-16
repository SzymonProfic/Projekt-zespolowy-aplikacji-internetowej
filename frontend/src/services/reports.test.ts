import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createReport, deleteReport, getCategories, getMyReports, getReportById, getReports, resolveReport, updateReport, type ReportFilters } from './reports';

vi.mock('./auth', () => ({ getAccessToken: vi.fn() }));
import { getAccessToken } from './auth';

beforeEach(() => { vi.mocked(getAccessToken).mockReset().mockResolvedValue('access-token'); });

const filters: ReportFilters = {
  type: 'lost', status: 'ACTIVE', categoryCode: 'KEYS', centerLabel: 'Kraków', center: null, radiusKm: 5,
};

afterEach(() => vi.unstubAllGlobals());

function mockReports() {
  const fetchMock = vi.fn().mockImplementation(async () => Response.json({ content: [], page: 0, size: 12, totalElements: 0, totalPages: 0 }));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

it('wysyła środek i promień w metrach wraz z pozostałymi filtrami i stroną', async () => {
  const fetchMock = mockReports();
  await getReports({ ...filters, center: { latitude: 50.061, longitude: 19.937 }, radiusKm: 0.5 }, 2);
  const params = new URL(fetchMock.mock.calls[0][0]).searchParams;
  expect(Object.fromEntries(params)).toEqual({
    page: '2', size: '12', status: 'ACTIVE', type: 'lost', categoryCode: 'KEYS',
    latitude: '50.061', longitude: '19.937', radiusMeters: '500',
  });
  expect(params.has('location')).toBe(false);
});

it('bez środka nie wysyła częściowego filtra przestrzennego, niezależnie od promienia', async () => {
  const fetchMock = mockReports();
  await getReports({ ...filters, radiusKm: 100 }, 0);
  const params = new URL(fetchMock.mock.calls[0][0]).searchParams;
  for (const key of ['latitude', 'longitude', 'radiusMeters']) expect(params.has(key)).toBe(false);
});

it('po usunięciu obszaru pomija współrzędne również na kolejnych stronach', async () => {
  const fetchMock = mockReports();
  const selected = { ...filters, center: { latitude: 0, longitude: 0 }, radiusKm: 100 };
  await getReports(selected, 0);
  await getReports({ ...selected, center: null }, 1);
  const initial = new URL(fetchMock.mock.calls[0][0]).searchParams;
  expect(initial.get('latitude')).toBe('0');
  expect(initial.get('longitude')).toBe('0');
  expect(initial.get('radiusMeters')).toBe('100000');
  const cleared = new URL(fetchMock.mock.calls[1][0]).searchParams;
  expect(cleared.has('radiusMeters')).toBe(false);
  expect(cleared.get('page')).toBe('1');
});

it('pobiera moje ogłoszenia z chronionego endpointu z tokenem, filtrami i stronicowaniem', async () => {
  const fetchMock = mockReports();
  const controller = new AbortController();
  await getMyReports({ ...filters, status: 'ALL' }, 1, controller.signal);
  const [url, options] = fetchMock.mock.calls[0];
  expect(new URL(url).pathname).toBe('/api/reports/mine');
  expect(new URL(url).searchParams.get('status')).toBe('ALL');
  expect(new URL(url).searchParams.get('page')).toBe('1');
  expect(new URL(url).searchParams.has('authorId')).toBe(false);
  expect(options.headers.get('Authorization')).toBe('Bearer access-token');
  expect(options.signal).toBe(controller.signal);
  expect(options.cache).toBe('no-store');
});

it('bez tokenu nie pobiera prywatnych ogłoszeń ani nie przechodzi na listę publiczną', async () => {
  const fetchMock = mockReports();
  vi.mocked(getAccessToken).mockResolvedValue(null);
  await expect(getMyReports(filters, 0)).rejects.toMatchObject({ status: 401 });
  expect(fetchMock).not.toHaveBeenCalled();
});

it('zwraca błąd wygasłej sesji bez zastępowania moich ogłoszeń publicznymi', async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));
  vi.stubGlobal('fetch', fetchMock);
  await expect(getMyReports(filters, 0)).rejects.toMatchObject({ status: 401 });
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(new URL(fetchMock.mock.calls[0][0]).pathname).toBe('/api/reports/mine');
});

it('kończy zgłoszenie przez PATCH z wersją i tokenem oraz zwraca aktualny status', async () => {
  const updated = { id: 'report/1', version: 5, status: 'RESOLVED', recovered: true };
  const fetchMock = vi.fn().mockResolvedValue(Response.json(updated));
  vi.stubGlobal('fetch', fetchMock);
  const controller = new AbortController();
  await expect(resolveReport('report/1', 4, controller.signal)).resolves.toEqual(updated);
  const [url, options] = fetchMock.mock.calls[0];
  expect(new URL(url).pathname).toBe('/api/reports/report%2F1/status');
  expect(options.method).toBe('PATCH');
  expect(JSON.parse(options.body)).toEqual({ version: 4, status: 'RESOLVED' });
  expect(options.headers.get('Authorization')).toBe('Bearer access-token');
  expect(options.headers.get('Content-Type')).toBe('application/json');
  expect(options.signal).toBe(controller.signal);
  expect(options.cache).toBe('no-store');
});

it('bez tokenu nie wysyła zmiany statusu', async () => {
  const fetchMock = mockReports();
  vi.mocked(getAccessToken).mockResolvedValue(null);
  await expect(resolveReport('report-1', 0)).rejects.toMatchObject({ status: 401 });
  expect(fetchMock).not.toHaveBeenCalled();
});

it.each([401, 403, 404, 409, 500])('przekazuje błąd PATCH %i bez automatycznego ponawiania zmiany', async (status) => {
  const fetchMock = vi.fn().mockResolvedValue(Response.json({ detail: 'Operacja odrzucona' }, { status }));
  vi.stubGlobal('fetch', fetchMock);
  await expect(resolveReport('report-1', 2)).rejects.toMatchObject({ status, message: 'Operacja odrzucona' });
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it('zwraca błąd zmiany statusu również gdy odpowiedź serwera nie jest JSON-em', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Bad Gateway', { status: 502 })));
  await expect(resolveReport('report-1', 2)).rejects.toMatchObject({ status: 502 });
});


it('publiczne listy i kategorie nie wymagają sesji', async () => {
  const fetchMock = mockReports();
  await getReports(filters, 0);
  await getCategories();
  expect(getAccessToken).not.toHaveBeenCalled();
  for (const [, options] of fetchMock.mock.calls) expect(options.headers.has('Authorization')).toBe(false);
});

it('szczegóły są dostępne bez logowania, a zalogowany użytkownik przekazuje token', async () => {
  const fetchMock = mockReports();
  vi.mocked(getAccessToken).mockResolvedValueOnce(null);
  await getReportById('report-1');
  await getReportById('report-1');
  expect(fetchMock.mock.calls[0][1].headers.has('Authorization')).toBe(false);
  expect(fetchMock.mock.calls[1][1].headers.get('Authorization')).toBe('Bearer access-token');
});

it('tworzy i edytuje zgłoszenie z aktualnym tokenem oraz danymi JSON', async () => {
  const fetchMock = mockReports();
  const content = { title: 'Klucze', description: 'Klucze z brelokiem', categoryCode: 'KEYS', color: null,
    eventDate: '2026-11-01', location: { label: 'Kraków', latitude: 50, longitude: 19 } };
  await createReport({ type: 'lost', ...content });
  await updateReport('report-1', { version: 3, content });
  const [createUrl, createOptions] = fetchMock.mock.calls[0];
  const [updateUrl, updateOptions] = fetchMock.mock.calls[1];
  expect(new URL(createUrl).pathname).toBe('/api/reports');
  expect(createOptions.method).toBe('POST');
  expect(JSON.parse(createOptions.body)).toEqual({ type: 'lost', ...content });
  expect(new URL(updateUrl).pathname).toBe('/api/reports/report-1');
  expect(updateOptions.method).toBe('PUT');
  expect(JSON.parse(updateOptions.body)).toEqual({ version: 3, content });
  expect(getAccessToken).toHaveBeenCalledTimes(2);
});

it('usuwa zgłoszenie przez DELETE z wersją w query i akceptuje pustą odpowiedź 204', async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal('fetch', fetchMock);
  const controller = new AbortController();
  await expect(deleteReport('report/1', 0, controller.signal)).resolves.toBeUndefined();
  const [url, options] = fetchMock.mock.calls[0];
  expect(new URL(url).pathname).toBe('/api/reports/report%2F1');
  expect(new URL(url).searchParams.get('version')).toBe('0');
  expect(options.method).toBe('DELETE');
  expect(options.headers.get('Authorization')).toBe('Bearer access-token');
  expect(options.headers.has('Content-Type')).toBe(false);
  expect(options.body).toBeUndefined();
  expect(options.signal).toBe(controller.signal);
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it('bez sesji nie wysyła żądania usunięcia zgłoszenia', async () => {
  const fetchMock = mockReports();
  vi.mocked(getAccessToken).mockResolvedValue(null);
  await expect(deleteReport('report-1', 3)).rejects.toMatchObject({ status: 401 });
  expect(fetchMock).not.toHaveBeenCalled();
});

it.each([401, 403, 404, 409, 500])('przekazuje błąd DELETE %i bez ponawiania operacji', async (status) => {
  const fetchMock = vi.fn().mockResolvedValue(Response.json({ detail: 'Usunięcie odrzucone' }, { status }));
  vi.stubGlobal('fetch', fetchMock);
  await expect(deleteReport('report-1', 3)).rejects.toMatchObject({ status, message: 'Usunięcie odrzucone' });
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it('nie ponawia usuwania po zerwaniu połączenia', async () => {
  const failure = new TypeError('Failed to fetch');
  const fetchMock = vi.fn().mockRejectedValue(failure);
  vi.stubGlobal('fetch', fetchMock);
  await expect(deleteReport('report-1', 3)).rejects.toBe(failure);
  expect(fetchMock).toHaveBeenCalledTimes(1);
});
