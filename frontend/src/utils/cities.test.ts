import { expect, it } from 'vitest';
import { findCities } from './cities';
import { getReportFormPoint, validateReportForm, type ReportFormValues } from './reportForm';

it('wyszukuje miasta bez polskich znaków, niezależnie od wielkości liter', () => {
  const [city] = findCities('  CZESTOCHOWA  ');
  expect(city.label).toBe('Częstochowa');
  expect(city.point).toEqual({ latitude: 50.811932, longitude: 19.127899 });
  expect(findCities('lodz')[0].label).toBe('Łódź');
});

it('rozróżnia miasta o tej samej nazwie przez województwo i współrzędne', () => {
  const cities = findCities('Olsztyn').filter(({ label }) => label.startsWith('Olsztyn,'));
  expect(cities).toHaveLength(2);
  expect(cities.map(({ label }) => label)).toEqual(expect.arrayContaining(['Olsztyn, śląskie', 'Olsztyn, warmińsko-mazurskie']));
  expect(cities[0].point).not.toEqual(cities[1].point);
});

it('ogranicza liczbę sugestii i nie wybiera punktu dla nieznanego miasta', () => {
  expect(findCities('')).toEqual([]);
  expect(findCities('a')).toEqual([]);
  expect(findCities('Nieistniejące miasto')).toEqual([]);
  expect(findCities('po').length).toBeLessThanOrEqual(8);
  expect(findCities('Poznań')[0].label).toBe('Poznań');
});

it('przekazuje punkt wybranej podpowiedzi do formularza i pozwala go doprecyzować', () => {
  const city = findCities('Olsztyn, slaskie')[0];
  const values: ReportFormValues = {
    type: 'lost', title: 'Klucze z brelokiem', description: 'Zgubiono klucze przy wejściu do parku.',
    categoryCode: 'KEYS', color: '', eventDate: '2026-01-01', locationLabel: city.label,
    initialLocation: { label: city.label, ...city.point }, selectedPoint: null,
  };
  expect(getReportFormPoint(values)).toMatchObject(city.point);
  const result = validateReportForm(values, '2026-01-02', ['KEYS']);
  expect(result.ok).toBe(true);
  if (result.ok) expect(result.content.location).toEqual({ label: city.label, ...city.point });
  const selectedPoint = { latitude: city.point.latitude + 0.001, longitude: city.point.longitude + 0.001 };
  expect(getReportFormPoint({ ...values, selectedPoint })).toEqual(selectedPoint);
  const nextCity = findCities('Częstochowa')[0];
  expect(getReportFormPoint({
    ...values, locationLabel: nextCity.label, initialLocation: { label: nextCity.label, ...nextCity.point }, selectedPoint: null,
  })).toMatchObject(nextCity.point);
});
