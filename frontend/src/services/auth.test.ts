import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const fixture = vi.hoisted(() => ({
  client: {
    authenticated: true,
    token: 'access-token',
    init: vi.fn(), updateToken: vi.fn(), login: vi.fn(), logout: vi.fn(), clearToken: vi.fn(),
    onAuthRefreshSuccess: undefined as (() => void) | undefined,
    onAuthLogout: undefined as (() => void) | undefined,
    onTokenExpired: undefined as (() => void) | undefined,
  },
}));

vi.mock('keycloak-js', () => ({ default: vi.fn(function () { return fixture.client; }) }));

const profile = {
  id: 'user-id', displayName: 'Alicja', email: 'alice@example.test', emailVerified: true,
  createdAt: '2026-01-01T12:00:00Z', updatedAt: '2026-01-01T12:00:00Z',
};

beforeEach(() => {
  vi.resetModules();
  vi.clearAllMocks();
  fixture.client.authenticated = true;
  fixture.client.token = 'access-token';
  fixture.client.init.mockResolvedValue(true);
  fixture.client.updateToken.mockResolvedValue(false);
  vi.stubGlobal('window', { location: { origin: 'http://localhost:5173' } });
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => Response.json(profile, { status: 201 })));
});

afterEach(() => { vi.unstubAllGlobals(); });

describe('profile initialization after Keycloak sign-in', () => {
  it('initializes only once and PUTs a profile without client-supplied identity fields', async () => {
    const auth = await import('./auth');
    await Promise.all([auth.initializeAuth(), auth.initializeAuth()]);
    expect(fixture.client.init).toHaveBeenCalledTimes(1);
    expect(fixture.client.init).toHaveBeenCalledWith(expect.objectContaining({ pkceMethod: 'S256', scope: 'openid profile email' }));
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledWith('http://localhost:8080/api/me', {
      method: 'PUT', headers: { Authorization: 'Bearer access-token' }, cache: 'no-store',
    });
    expect(auth.getAuthSnapshot()).toMatchObject({ status: 'ready', profile, signedIn: true });
  });

  it('does not create a profile for anonymous visitors', async () => {
    fixture.client.authenticated = false;
    fixture.client.init.mockResolvedValue(false);
    const auth = await import('./auth');
    await auth.initializeAuth();
    expect(fetch).not.toHaveBeenCalled();
    expect(auth.getAuthSnapshot().status).toBe('anonymous');
  });

  it('blocks profile readiness on API failure and allows retry', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(Response.json({}, { status: 503 }));
    const auth = await import('./auth');
    await auth.initializeAuth();
    expect(auth.getAuthSnapshot()).toMatchObject({ status: 'error', profile: null, signedIn: true });
    await auth.retryProfile();
    expect(auth.getAuthSnapshot()).toMatchObject({ status: 'ready', profile });
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('resynchronizes after token refresh using the new token', async () => {
    const auth = await import('./auth');
    await auth.initializeAuth();
    fixture.client.token = 'refreshed-token';
    fixture.client.onAuthRefreshSuccess?.();
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
    await vi.waitFor(() => expect(auth.getAuthSnapshot().status).toBe('ready'));
    expect(fetch).toHaveBeenLastCalledWith('http://localhost:8080/api/me', expect.objectContaining({
      headers: { Authorization: 'Bearer refreshed-token' },
    }));
  });

  it('ignores an in-flight profile response after logout', async () => {
    let resolve!: (response: Response) => void;
    vi.mocked(fetch).mockImplementation(() => new Promise<Response>((done) => { resolve = done; }));
    const auth = await import('./auth');
    const initialized = auth.initializeAuth();
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    fixture.client.authenticated = false;
    fixture.client.onAuthLogout?.();
    resolve(Response.json(profile));
    await initialized;
    expect(auth.getAuthSnapshot()).toMatchObject({ status: 'anonymous', signedIn: false, profile: null });
  });

  it('allows retry when Keycloak initialization was unavailable', async () => {
    fixture.client.init.mockRejectedValueOnce(new Error('offline'));
    const auth = await import('./auth');
    await auth.initializeAuth();
    expect(auth.getAuthSnapshot().status).toBe('error');
    await auth.retryProfile();
    expect(auth.getAuthSnapshot().status).toBe('ready');
    expect(fixture.client.init).toHaveBeenCalledTimes(2);
  });
});
