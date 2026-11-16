import type { Category, CreateReportRequest, ReportDetails, ReportLocationPoint, UpdateReportRequest } from '../types/report';
import { apiRequest } from './apiClient';
import { authenticatedRequest } from './authenticatedApi';

export function getReportById(id: string, signal?: AbortSignal) {
  return authenticatedRequest<ReportDetails>(`/api/reports/${encodeURIComponent(id)}`, { auth: 'optional', signal });
}

export function createReport(report: CreateReportRequest, signal?: AbortSignal) {
  return authenticatedRequest<ReportDetails>('/api/reports', { method: 'POST', json: report, signal });
}

export function updateReport(id: string, report: UpdateReportRequest, signal?: AbortSignal) {
  return authenticatedRequest<ReportDetails>(`/api/reports/${encodeURIComponent(id)}`, {
    method: 'PUT', json: report, signal,
  });
}

export function resolveReport(id: string, version: number, signal?: AbortSignal) {
  return authenticatedRequest<ReportDetails>(`/api/reports/${encodeURIComponent(id)}/status`, {
    method: 'PATCH', json: { version, status: 'RESOLVED' }, signal,
  });
}

export function deleteReport(id: string, version: number, signal?: AbortSignal) {
  const params = new URLSearchParams({ version: String(version) });
  return authenticatedRequest<void>(`/api/reports/${encodeURIComponent(id)}?${params}`, {
    method: 'DELETE', signal,
  });
}

export interface ReportFilters {
  type: '' | 'lost' | 'found';
  status: 'ACTIVE' | 'RESOLVED' | 'ALL';
  categoryCode: string;
  centerLabel: string;
  center: ReportLocationPoint | null;
  radiusKm: number;
}

interface ReportPage {
  content: ReportDetails[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export function getCategories(signal?: AbortSignal) {
  return apiRequest<Category[]>('/api/categories', { signal });
}

function reportSearchParams(filters: ReportFilters, page: number) {
  const params = new URLSearchParams({ page: String(page), size: '12', status: filters.status });
  if (filters.type) params.set('type', filters.type);
  if (filters.categoryCode) params.set('categoryCode', filters.categoryCode);
  if (filters.center) {
    params.set('latitude', String(filters.center.latitude));
    params.set('longitude', String(filters.center.longitude));
    params.set('radiusMeters', String(filters.radiusKm * 1000));
  }
  return params.toString();
}

export function getReports(filters: ReportFilters, page: number, signal?: AbortSignal) {
  return apiRequest<ReportPage>(`/api/reports?${reportSearchParams(filters, page)}`, { signal });
}

export function getMyReports(filters: ReportFilters, page: number, signal?: AbortSignal) {
  return authenticatedRequest<ReportPage>(`/api/reports/mine?${reportSearchParams(filters, page)}`, { signal });
}
