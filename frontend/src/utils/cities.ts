import cityPoints from '../data/polishCities.json' with { type: 'json' };
import type { ReportLocationPoint } from '../types/report';

export interface CitySuggestion {
  label: string;
  point: ReportLocationPoint;
}

export function normalizeCityName(name: string) {
  return name.trim().toLowerCase().normalize('NFD').replace(/\p{M}/gu, '').replaceAll('ł', 'l').replace(/\s+/g, ' ');
}

const cities = Object.entries(cityPoints).flatMap(([name, entry]): CitySuggestion[] => (
  Array.isArray(entry)
    ? entry.map(({ region, latitude, longitude }) => ({ label: `${name}, ${region}`, point: { latitude, longitude } }))
    : [{ label: name, point: entry }]
)).map((city) => ({ ...city, searchLabel: normalizeCityName(city.label) }));

export function findCities(query: string): CitySuggestion[] {
  const search = normalizeCityName(query);
  if (search.length < 2) return [];
  const rank = (label: string) => label === search ? 0 : label.startsWith(search) ? 1 : 2;
  return cities.filter(({ searchLabel }) => searchLabel.includes(search))
    .sort((a, b) => rank(a.searchLabel) - rank(b.searchLabel) || a.label.localeCompare(b.label, 'pl'))
    .slice(0, 8);
}
