const apiUrl = (import.meta.env.VITE_API_URL ?? 'http://localhost:8080').replace(/\/+$/, '');

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

export interface ApiRequestOptions extends Omit<RequestInit, 'body'> {
  json?: unknown;
  accessToken?: string | null;
}

/** JSON transport shared by all API services. Network errors and cancellation pass through unchanged. */
export async function apiRequest<T = void>(path: string, {
  json, accessToken, ...options
}: ApiRequestOptions = {}): Promise<T> {
  options.signal?.throwIfAborted();
  const headers = new Headers(options.headers);
  if (!headers.has('Accept')) headers.set('Accept', 'application/json');
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
  if (json !== undefined) headers.set('Content-Type', 'application/json');

  const response = await fetch(`${apiUrl}${path}`, {
    cache: 'no-store',
    ...options,
    headers,
    ...(json !== undefined ? { body: JSON.stringify(json) } : {}),
  });
  if (!response.ok) {
    const problem: unknown = await response.json().catch((error: unknown) => {
      options.signal?.throwIfAborted();
      if (error instanceof Error && error.name === 'AbortError') throw error;
      return null;
    });
    const detail = problem && typeof problem === 'object' && 'detail' in problem
      && typeof problem.detail === 'string' && problem.detail.trim()
      ? problem.detail : 'Nie udało się wykonać operacji.';
    throw new ApiError(response.status, detail);
  }
  // Callers of endpoints without a response body use apiRequest<void>().
  if (response.status === 204 || response.status === 205) return undefined as T;
  return response.json() as Promise<T>;
}
