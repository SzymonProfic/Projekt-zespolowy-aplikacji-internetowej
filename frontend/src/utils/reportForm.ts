import type { Report, ReportContent } from '../types/report';

export interface ReportFormValues {
  type: Report['type'];
  title: string;
  categoryCode: string;
  description: string;
  color: string;
  eventDate: string;
  locationLabel: string;
  latitude: string;
  longitude: string;
}

export type ReportFormField = Exclude<keyof ReportFormValues, 'type'>;
export type ReportFormErrors = Partial<Record<ReportFormField, string>>;

const warsawDateFormatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Europe/Warsaw',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

export function todayInWarsaw(now = new Date()) {
  const parts = Object.fromEntries(warsawDateFormatter.formatToParts(now).map(({ type, value }) => [type, value]));
  return `${parts.year}-${parts.month}-${parts.day}`;
}

function parseCoordinate(input: string) {
  const value = input.trim().replace(',', '.');
  return /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(value) ? Number(value) : NaN;
}

function isCalendarDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

export function validateReportForm(values: ReportFormValues, today: string, categoryCodes: string[]):
  { ok: true; content: ReportContent } | { ok: false; errors: ReportFormErrors } {
  const title = values.title.trim();
  const description = values.description.trim();
  const color = values.color.trim();
  const label = values.locationLabel.trim();
  const latitude = parseCoordinate(values.latitude);
  const longitude = parseCoordinate(values.longitude);
  const errors: ReportFormErrors = {};

  if (title.length < 3 || title.length > 150) errors.title = 'Podaj nazwę przedmiotu (od 3 do 150 znaków).';
  if (!categoryCodes.includes(values.categoryCode)) errors.categoryCode = 'Wybierz dostępną kategorię.';
  if (color.length > 80) errors.color = 'Kolor może mieć maksymalnie 80 znaków.';
  if (description.length < 10 || description.length > 5000) errors.description = 'Podaj opis (od 10 do 5000 znaków).';
  if (label.length < 3 || label.length > 255) errors.locationLabel = 'Podaj miejsce zdarzenia (od 3 do 255 znaków).';
  if (!isCalendarDate(values.eventDate)) errors.eventDate = 'Podaj poprawną datę zdarzenia.';
  else if (values.eventDate > today) errors.eventDate = 'Data zdarzenia nie może być w przyszłości.';
  if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90) errors.latitude = 'Podaj szerokość od −90 do 90.';
  if (!Number.isFinite(longitude) || longitude < -180 || longitude > 180) errors.longitude = 'Podaj długość od −180 do 180.';

  if (Object.keys(errors).length > 0) return { ok: false, errors };

  return {
    ok: true,
    content: {
      title,
      categoryCode: values.categoryCode,
      description,
      color: color || null,
      eventDate: values.eventDate,
      location: { label, latitude, longitude },
    },
  };
}
