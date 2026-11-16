import { afterEach, expect, it, vi } from 'vitest';
import { apiRequest, ApiError } from './apiClient';

afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.resetModules(); });

it('uses the configured API URL, normalizes trailing slashes and preserves query parameters', async () => {
  vi.resetModules();
  vi.stubEnv('VITE_API_URL', 'https://api.example.test/base///');
  const fetchMock = vi.fn().mockResolvedValue(Response.json({ id: 1 }));
  vi.stubGlobal('fetch', fetchMock);
  const { apiRequest: configuredRequest } = await import('./apiClient');
  await expect(configuredRequest('/reports?q=klucze')).resolves.toEqual({ id: 1 });
  expect(fetchMock.mock.calls[0][0]).toBe('https://api.example.test/base/reports?q=klucze');
});

it('merges headers without mutating them and serializes JSON, including null', async () => {
  const fetchMock = vi.fn().mockImplementation(async () => Response.json({ saved: true }));
  vi.stubGlobal('fetch', fetchMock);
  const headers = new Headers({ 'X-Request-ID': 'request-1' });
  await apiRequest('/api/example', { method: 'POST', headers, json: null, accessToken: 'token' });
  const options = fetchMock.mock.calls[0][1];
  expect(Object.fromEntries(options.headers)).toEqual({
    accept: 'application/json', authorization: 'Bearer token', 'content-type': 'application/json', 'x-request-id': 'request-1',
  });
  expect(options.body).toBe('null');
  expect(headers.has('Authorization')).toBe(false);
});

it.each([204, 205])('accepts an empty %i response without parsing JSON or adding a body', async (status) => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status }));
  vi.stubGlobal('fetch', fetchMock);
  await expect(apiRequest<void>('/api/example', { method: 'DELETE' })).resolves.toBeUndefined();
  const options = fetchMock.mock.calls[0][1];
  expect(options.body).toBeUndefined();
  expect(options.headers.has('Content-Type')).toBe(false);
  expect(options.headers.has('Authorization')).toBe(false);
});

it('preserves status and Problem Detail errors without retrying the request', async () => {
  const fetchMock = vi.fn().mockResolvedValue(Response.json({ detail: 'Konflikt wersji' }, { status: 409 }));
  vi.stubGlobal('fetch', fetchMock);
  await expect(apiRequest('/api/example', { method: 'PATCH', json: { version: 1 } }))
    .rejects.toMatchObject({ name: 'ApiError', status: 409, message: 'Konflikt wersji' });
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it.each(['Bad Gateway', '', '{}', '{"detail":42}', '{"detail":"  "}'])('handles nonstandard error bodies: %s', async (body) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { status: 502 })));
  await expect(apiRequest('/api/example')).rejects.toMatchObject({ status: 502, message: 'Nie udało się wykonać operacji.' });
});

it('leaves network errors distinct from HTTP errors', async () => {
  const failure = new TypeError('Failed to fetch');
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(failure));
  await expect(apiRequest('/api/example')).rejects.toBe(failure);
  expect(failure).not.toBeInstanceOf(ApiError);
});

it('rejects already cancelled requests without sending them', async () => {
  const fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  const controller = new AbortController();
  controller.abort();
  await expect(apiRequest('/api/example', { signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' });
  expect(fetchMock).not.toHaveBeenCalled();
});

it('passes cancellation through fetch and response error parsing', async () => {
  const controller = new AbortController();
  const response = new Response(null, { status: 400 });
  vi.spyOn(response, 'json').mockImplementation(async () => { controller.abort(); throw controller.signal.reason; });
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal('fetch', fetchMock);
  await expect(apiRequest('/api/example', { signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' });
  expect(fetchMock.mock.calls[0][1].signal).toBe(controller.signal);
});
