import { apiRequest, ApiError, type ApiRequestOptions } from './apiClient';
import { getAccessToken } from './auth';

interface AuthenticatedRequestOptions extends Omit<ApiRequestOptions, 'accessToken'> {
  auth?: 'required' | 'optional';
}

/** Resolve a refreshed token before sending a request; never retry mutations automatically. */
export async function authenticatedRequest<T = void>(path: string, {
  auth = 'required', ...options
}: AuthenticatedRequestOptions = {}): Promise<T> {
  options.signal?.throwIfAborted();
  const accessToken = await getAccessToken();
  options.signal?.throwIfAborted();
  if (auth === 'required' && !accessToken) throw new ApiError(401, 'Brak aktywnej sesji');
  return apiRequest<T>(path, { ...options, accessToken });
}
