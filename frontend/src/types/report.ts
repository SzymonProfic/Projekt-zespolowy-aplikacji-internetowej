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
