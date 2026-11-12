import cityPoints from '../data/polishCities.json' with { type: 'json' };
import type { Report, ReportContent, ReportDetails, ReportLocation, ReportLocationPoint } from '../types/report';

export interface ReportFormValues {
  type: Report['type'];
  title: string;
  categoryCode: string;
  description: string;
  color: string;
  eventDate: string;
  locationLabel: string;
  initialLocation: ReportLocation | null;
}

export type ReportFormField = Exclude<keyof ReportFormValues, 'type' | 'initialLocation'>;
export type ReportFormErrors = Partial<Record<ReportFormField, string>>;

type EditableReport = ReportDetails & { editableLocation: ReportLocation };

export function canEditReport(report: ReportDetails): report is EditableReport {
  return report.owner && report.status === 'ACTIVE' && report.editableLocation !== null;
}

export function reportToFormValues(report: EditableReport): ReportFormValues {
  return {
    type: report.type,
    title: report.title,
    categoryCode: report.categoryCode,
    description: report.description,
    color: report.color,
    eventDate: report.date,
    locationLabel: report.editableLocation.label,
    initialLocation: report.editableLocation,
  };
}

function normalizeCityName(name: string) {
  return name
    .trim()
    .toLowerCase()
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .replaceAll('ł', 'l')
    .replace(/\s+/g, ' ');
}

type CityInRegion = ReportLocationPoint & { region: string };

const pointsByCity = new Map<string, ReportLocationPoint | CityInRegion[]>(
  Object.entries(cityPoints).map(([city, point]) => [normalizeCityName(city), point]),
);

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
  const [name, region] = label.split(',', 2).map(normalizeCityName);
  const city = pointsByCity.get(name);
  const cityPoint = Array.isArray(city) ? city.find((entry) => normalizeCityName(entry.region) === region) : city;
  const point = values.initialLocation?.label.trim() === label ? values.initialLocation : cityPoint;
  const errors: ReportFormErrors = {};

  if (title.length < 3 || title.length > 150) errors.title = 'Podaj nazwę przedmiotu (od 3 do 150 znaków).';
  if (!categoryCodes.includes(values.categoryCode)) errors.categoryCode = 'Wybierz dostępną kategorię.';
  if (color.length > 80) errors.color = 'Kolor może mieć maksymalnie 80 znaków.';
  if (description.length < 10 || description.length > 5000) errors.description = 'Podaj opis (od 10 do 5000 znaków).';
  if (label.length < 3 || label.length > 255) {
    errors.locationLabel = 'Podaj miejsce zdarzenia (od 3 do 255 znaków).';
  } else if (!point) {
    errors.locationLabel = Array.isArray(city)
      ? `Dopisz po przecinku województwo (${city.map((entry) => entry.region).join(' lub ')}).`
      : 'Podaj nazwę miasta w Polsce, np. Częstochowa. Dzielnicę lub ulicę możesz dopisać po przecinku.';
  }
  if (!isCalendarDate(values.eventDate)) errors.eventDate = 'Podaj poprawną datę zdarzenia.';
  else if (values.eventDate > today) errors.eventDate = 'Data zdarzenia nie może być w przyszłości.';

  if (Object.keys(errors).length > 0 || !point) return { ok: false, errors };

  return {
    ok: true,
    content: {
      title,
      categoryCode: values.categoryCode,
      description,
      color: color || null,
      eventDate: values.eventDate,
      location: { label, latitude: point.latitude, longitude: point.longitude },
    },
  };
}
