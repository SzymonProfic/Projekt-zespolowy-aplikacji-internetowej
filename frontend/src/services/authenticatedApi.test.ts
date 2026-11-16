import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { authenticatedRequest } from './authenticatedApi';
import { getAccessToken } from './auth';

vi.mock('./auth', () => ({ getAccessToken: vi.fn() }));

beforeEach(() => {
  vi.mocked(getAccessToken).mockReset().mockResolvedValue('fresh-token');
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => Response.json({ ok: true })));
});
afterEach(() => vi.unstubAllGlobals());

it('gets the current token separately for each request', async () => {
  vi.mocked(getAccessToken).mockResolvedValueOnce('first-token');
  await authenticatedRequest('/api/example');
  await authenticatedRequest('/api/example');
  const calls = vi.mocked(fetch).mock.calls;
  expect(new Headers(calls[0][1]?.headers).get('Authorization')).toBe('Bearer first-token');
  expect(new Headers(calls[1][1]?.headers).get('Authorization')).toBe('Bearer fresh-token');
});

it('does not send a protected request when the session is unavailable', async () => {
  vi.mocked(getAccessToken).mockResolvedValue(null);
  await expect(authenticatedRequest('/api/example')).rejects.toMatchObject({ status: 401 });
  expect(fetch).not.toHaveBeenCalled();
});

it('allows anonymous access only when explicitly optional', async () => {
  vi.mocked(getAccessToken).mockResolvedValue(null);
  await expect(authenticatedRequest('/api/example', { auth: 'optional' })).resolves.toEqual({ ok: true });
  expect(new Headers(vi.mocked(fetch).mock.calls[0][1]?.headers).has('Authorization')).toBe(false);
});

it('does not start session initialization for an already cancelled request', async () => {
  const controller = new AbortController();
  controller.abort();
  await expect(authenticatedRequest('/api/example', { signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' });
  expect(getAccessToken).not.toHaveBeenCalled();
  expect(fetch).not.toHaveBeenCalled();
});

it('does not send a request cancelled while waiting for a token', async () => {
  let provideToken!: (token: string) => void;
  vi.mocked(getAccessToken).mockImplementation(() => new Promise((resolve) => { provideToken = resolve; }));
  const controller = new AbortController();
  const pending = authenticatedRequest('/api/example', { signal: controller.signal });
  controller.abort();
  provideToken('token');
  await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  expect(fetch).not.toHaveBeenCalled();
});

it('does not retry a mutation or redirect on a server-side 401', async () => {
  vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 401 }));
  await expect(authenticatedRequest('/api/example', { method: 'POST', json: {} })).rejects.toMatchObject({ status: 401 });
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(getAccessToken).toHaveBeenCalledTimes(1);
});
