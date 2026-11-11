import type { Category, Report, ReportDetails } from '../types/report';

const apiUrl = (import.meta.env.VITE_API_URL ?? 'http://localhost:8080').replace(/\/$/, '');

export class ReportRequestError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
    this.name = 'ReportRequestError';
  }
}

async function requestReport(
  path: string,
  { accessToken, ...options }: RequestInit & { accessToken?: string | null } = {},
): Promise<ReportDetails> {
  const headers = new Headers(options.headers);
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
  if (options.body) headers.set('Content-Type', 'application/json');

  const response = await fetch(`${apiUrl}${path}`, { ...options, headers, cache: 'no-store' });
  if (!response.ok) {
    const problem: unknown = await response.json().catch(() => null);
    const detail = problem && typeof problem === 'object' && 'detail' in problem && typeof problem.detail === 'string'
      ? problem.detail
      : 'Nie udało się wykonać operacji.';
    throw new ReportRequestError(response.status, detail);
  }
  return response.json() as Promise<ReportDetails>;
}

export function getReportById(id: string, accessToken: string | null, signal?: AbortSignal) {
  return requestReport(`/api/reports/${encodeURIComponent(id)}`, { accessToken, signal });
}

export interface ReportFilters {
  type: '' | 'lost' | 'found';
  status: 'ACTIVE' | 'RESOLVED' | 'ALL';
  categoryCode: string;
  latitude: string;
  longitude: string;
  radiusKm: string;
}

interface ReportPage {
  content: Report[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export async function getCategories(): Promise<Category[]> {
  const response = await fetch(`${apiUrl}/api/categories`, { cache: 'no-store' });
  if (!response.ok) throw new Error('Categories unavailable');
  return response.json() as Promise<Category[]>;
}

export async function getReports(filters: ReportFilters, page: number): Promise<ReportPage> {
  const params = new URLSearchParams({ page: String(page), size: '12', status: filters.status });
  if (filters.type) params.set('type', filters.type);
  if (filters.categoryCode) params.set('categoryCode', filters.categoryCode);
  if (filters.latitude && filters.longitude && filters.radiusKm) {
    params.set('latitude', filters.latitude);
    params.set('longitude', filters.longitude);
    params.set('radiusMeters', String(Number(filters.radiusKm) * 1000));
  }
  const response = await fetch(`${apiUrl}/api/reports?${params.toString()}`, { cache: 'no-store' });
  if (!response.ok) throw new Error('Reports unavailable');
  return response.json() as Promise<ReportPage>;
}
