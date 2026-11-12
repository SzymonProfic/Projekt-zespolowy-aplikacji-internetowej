export interface Report {
  id: string;
  title: string;
  type: 'lost' | 'found';
  category: string;
  location: string;
  /** Data zdarzenia w formacie YYYY-MM-DD. */
  date: string;
  color: string;
  description: string;
  owner?: boolean;
  recovered?: boolean;
}

export interface Category {
  id: string;
  code: string;
  name: string;
}

export interface ReportLocation {
  label: string;
  longitude: number;
  latitude: number;
}

export type ReportLocationPoint = Pick<ReportLocation, 'latitude' | 'longitude'>;

export interface ReportDetails extends Report {
  categoryCode: string;
  owner: boolean;
  status: 'ACTIVE' | 'RESOLVED';
  version: number;
  editableLocation: ReportLocation | null;
}

export interface ReportContent {
  title: string;
  categoryCode: string;
  description: string;
  color: string | null;
  eventDate: string;
  location: ReportLocation;
}

export interface CreateReportRequest extends ReportContent {
  type: Report['type'];
}

export interface UpdateReportRequest {
  version: number;
  content: ReportContent;
}
