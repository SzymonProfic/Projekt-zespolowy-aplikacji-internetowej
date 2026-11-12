import assert from 'node:assert/strict';
import { test } from 'node:test';
import { canEditReport, reportToFormValues, todayInWarsaw, validateReportForm } from '../src/utils/reportForm.ts';

const today = '2026-11-11';
const categories = ['KEYS', 'OTHER'];
const validValues = {
  type: 'found',
  title: ' Klucze z zielonym brelokiem ',
  categoryCode: 'KEYS',
  description: ' Znaleziono klucze przy wejściu do parku. ',
  color: ' ',
  eventDate: '2026-11-10',
  locationLabel: ' Warszawa, Śródmieście ',
  locationPoint: { latitude: 0, longitude: 21.0122 },
};

test('przygotowuje etykietę i punkt wybrany na mapie, także dla współrzędnej zero', () => {
  const result = validateReportForm(validValues, today, categories);
  assert.equal(result.ok, true);
  assert.deepEqual(result.content, {
    title: 'Klucze z zielonym brelokiem',
    categoryCode: 'KEYS',
    description: 'Znaleziono klucze przy wejściu do parku.',
    color: null,
    eventDate: '2026-11-10',
    location: { label: 'Warszawa, Śródmieście', latitude: 0, longitude: 21.0122 },
  });
});

test('zgłasza brak wymaganych pól', () => {
  const result = validateReportForm({
    ...validValues, title: ' ', description: '', categoryCode: '', eventDate: '',
    locationLabel: '', locationPoint: null,
  }, today, categories);
  assert.equal(result.ok, false);
  assert.deepEqual(Object.keys(result.errors).sort(), [
    'categoryCode', 'description', 'eventDate', 'locationLabel', 'title',
  ]);
});

test('odrzuca kategorie spoza API oraz nieprawidłowy punkt na mapie', () => {
  const result = validateReportForm({
    ...validValues, categoryCode: 'INACTIVE', locationPoint: { latitude: 90.001, longitude: -180.001 },
  }, today, categories);
  assert.equal(result.ok, false);
  assert.deepEqual(Object.keys(result.errors).sort(), ['categoryCode', 'locationLabel']);
});

test('wymaga wyboru pinezki po podaniu nazwy miejsca', () => {
  const result = validateReportForm({ ...validValues, locationPoint: null }, today, categories);
  assert.deepEqual(result, {
    ok: false,
    errors: { locationLabel: 'Zaznacz miejsce zdarzenia na mapie.' },
  });
});

test('odrzuca daty z przyszłości oraz nieistniejące dni kalendarza', () => {
  for (const eventDate of ['2026-11-12', '2026-02-31']) {
    const result = validateReportForm({ ...validValues, eventDate }, today, categories);
    assert.equal(result.ok, false);
    assert.ok(result.errors.eventDate);
  }
});

test('waliduje datę według dnia w Warszawie, również po lokalnej północy', () => {
  const localToday = todayInWarsaw(new Date('2026-11-10T23:30:00Z'));
  assert.equal(localToday, today);
  assert.equal(validateReportForm({ ...validValues, eventDate: today }, localToday, categories).ok, true);
});

test('udostępnia edycję tylko dla aktywnego zgłoszenia autora z danymi lokalizacji', () => {
  const report = {
    id: '6d520dba-43aa-4072-ac2f-18385442272d',
    title: 'Klucze z zielonym brelokiem',
    type: 'found',
    category: 'Klucze',
    categoryCode: 'KEYS',
    description: 'Znaleziono klucze przy wejściu do parku.',
    color: '',
    date: '2026-11-10',
    location: 'Warszawa',
    owner: true,
    status: 'ACTIVE',
    version: 4,
    editableLocation: { label: 'Warszawa, Śródmieście', latitude: 52.2297, longitude: 21.0122 },
  };
  assert.equal(canEditReport(report), true);
  assert.equal(canEditReport({ ...report, owner: false }), false);
  assert.equal(canEditReport({ ...report, status: 'RESOLVED' }), false);
  assert.equal(canEditReport({ ...report, editableLocation: null }), false);
  const fields = reportToFormValues(report);
  assert.equal(fields.eventDate, '2026-11-10');
  assert.equal(fields.locationLabel, 'Warszawa, Śródmieście');
  assert.deepEqual(fields.locationPoint, { latitude: 52.2297, longitude: 21.0122 });
});
