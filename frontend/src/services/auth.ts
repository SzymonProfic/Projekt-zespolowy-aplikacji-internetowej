import Keycloak from 'keycloak-js';

export interface Profile {
  id: string;
  displayName: string;
  email: string | null;
  emailVerified: boolean;
  createdAt: string;
  updatedAt: string;
}

interface AuthSnapshot {
  status: 'loading' | 'anonymous' | 'synchronizing' | 'ready' | 'error';
  signedIn: boolean;
  profile: Profile | null;
  error: string | null;
}

const apiUrl = (import.meta.env.VITE_API_URL ?? 'http://localhost:8080').replace(/\/$/, '');
let snapshot: AuthSnapshot = { status: 'loading', signedIn: false, profile: null, error: null };
let client: Keycloak | undefined;
let initialization: Promise<void> | undefined;
let initialized = false;
let generation = 0;
const listeners = new Set<() => void>();

function publish(change: Partial<AuthSnapshot>) {
  snapshot = { ...snapshot, ...change };
  listeners.forEach((listener) => listener());
}

export function subscribeAuth(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

export function getAuthSnapshot() { return snapshot; }

async function synchronizeProfile() {
  const token = client?.token;
  if (!client?.authenticated || !token) return;
  const current = ++generation;
  publish({ status: 'synchronizing', signedIn: true, profile: null, error: null });
  try {
    const response = await fetch(`${apiUrl}/api/me`, {
      method: 'PUT',
      headers: { Authorization: `Bearer ${token}` },
      cache: 'no-store',
    });
    if (!response.ok) throw new Error('Profile synchronization failed');
    const profile: Profile = await response.json();
    if (current === generation && client.authenticated) {
      publish({ status: 'ready', signedIn: true, profile, error: null });
    }
  } catch {
    if (current === generation) {
      publish({ status: 'error', profile: null, error: 'Nie udało się przygotować Twojego profilu. Spróbuj ponownie.' });
    }
  }
}

function clearSession() {
  generation++;
  publish({ status: 'anonymous', signedIn: false, profile: null, error: null });
}

export function initializeAuth(): Promise<void> {
  if (initialization) return initialization;
  publish({ status: 'loading', signedIn: false, profile: null, error: null });
  client = new Keycloak({
    url: import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8081',
    realm: import.meta.env.VITE_KEYCLOAK_REALM ?? 'foundly',
    clientId: 'foundly-frontend',
  });
  const keycloak = client;
  keycloak.onAuthLogout = clearSession;
  keycloak.onAuthRefreshSuccess = () => { void synchronizeProfile(); };
  keycloak.onTokenExpired = () => {
    void keycloak.updateToken(30).catch(() => {
      keycloak.clearToken();
      clearSession();
      publish({ error: 'Sesja wygasła. Zaloguj się ponownie.' });
    });
  };
  initialization = keycloak.init({
    onLoad: 'check-sso',
    pkceMethod: 'S256',
    scope: 'openid profile email',
    checkLoginIframe: false,
  }).then(async (authenticated) => {
    initialized = true;
    if (authenticated) await synchronizeProfile();
    else clearSession();
  }).catch(() => {
    initialization = undefined;
    publish({ status: 'error', signedIn: false, profile: null, error: 'Logowanie jest chwilowo niedostępne. Spróbuj ponownie.' });
  });
  return initialization;
}

export async function retryProfile() {
  if (!initialized) { await initializeAuth(); return; }
  if (!client?.authenticated) { clearSession(); return; }
  try {
    // A refresh triggers synchronization through onAuthRefreshSuccess.
    const refreshed = await client.updateToken(30);
    if (!refreshed) await synchronizeProfile();
  } catch {
    client.clearToken();
    clearSession();
    publish({ error: 'Sesja wygasła. Zaloguj się ponownie.' });
  }
}

export async function login() {
  await initializeAuth();
  if (!initialized || !client) return;
  try {
    await client.login({ redirectUri: window.location.origin + '/', scope: 'openid profile email' });
  } catch {
    publish({ status: 'error', error: 'Nie udało się otworzyć logowania. Spróbuj ponownie.' });
  }
}

export async function register() {
  await initializeAuth();
  if (!initialized || !client) return;
  try {
    await client.register({ redirectUri: window.location.origin + '/', scope: 'openid profile email' });
  } catch {
    publish({ status: 'error', error: 'Nie udało się otworzyć rejestracji. Spróbuj ponownie.' });
  }
}

export async function logout() {
  try {
    await client?.logout({ redirectUri: window.location.origin + '/' });
  } catch {
    publish({ error: 'Nie udało się wylogować. Spróbuj ponownie.' });
  }
}
