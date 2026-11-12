import { afterEach, expect, it, vi } from 'vitest';
import { getMyReports, getReports, type ReportFilters } from './reports';

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
  await getMyReports({ ...filters, status: 'ALL' }, 1, 'user-access-token', controller.signal);
  const [url, options] = fetchMock.mock.calls[0];
  expect(new URL(url).pathname).toBe('/api/reports/mine');
  expect(new URL(url).searchParams.get('status')).toBe('ALL');
  expect(new URL(url).searchParams.get('page')).toBe('1');
  expect(new URL(url).searchParams.has('authorId')).toBe(false);
  expect(options.headers.get('Authorization')).toBe('Bearer user-access-token');
  expect(options.signal).toBe(controller.signal);
  expect(options.cache).toBe('no-store');
});

it('bez tokenu nie pobiera prywatnych ogłoszeń ani nie przechodzi na listę publiczną', async () => {
  const fetchMock = mockReports();
  await expect(getMyReports(filters, 0, '')).rejects.toMatchObject({ status: 401 });
  expect(fetchMock).not.toHaveBeenCalled();
});

it('zwraca błąd wygasłej sesji bez zastępowania moich ogłoszeń publicznymi', async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));
  vi.stubGlobal('fetch', fetchMock);
  await expect(getMyReports(filters, 0, 'expired-token')).rejects.toMatchObject({ status: 401 });
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(new URL(fetchMock.mock.calls[0][0]).pathname).toBe('/api/reports/mine');
});
