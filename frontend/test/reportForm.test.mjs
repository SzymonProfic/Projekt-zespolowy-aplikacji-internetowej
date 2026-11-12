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
  locationLabel: ' Częstochowa ',
  initialLocation: null,
};
const czestochowaPoint = { latitude: 50.811932, longitude: 19.127899 };

test('przygotowuje etykietę i orientacyjny punkt Częstochowy z samej nazwy', () => {
  const result = validateReportForm(validValues, today, categories);
  assert.equal(result.ok, true);
  assert.deepEqual(result.content, {
    title: 'Klucze z zielonym brelokiem',
    categoryCode: 'KEYS',
    description: 'Znaleziono klucze przy wejściu do parku.',
    color: null,
    eventDate: '2026-11-10',
    location: { label: 'Częstochowa', ...czestochowaPoint },
  });
});

test('rozpoznaje miasto bez polskich znaków i zachowuje wpisaną dzielnicę', () => {
  const locationLabel = 'CZESTOCHOWA, Raków';
  const result = validateReportForm({ ...validValues, locationLabel }, today, categories);
  assert.equal(result.ok, true);
  assert.deepEqual(result.content.location, { label: locationLabel, ...czestochowaPoint });
});

test('zgłasza brak wymaganych pól', () => {
  const result = validateReportForm({
    ...validValues, title: ' ', description: '', categoryCode: '', eventDate: '',
    locationLabel: '',
  }, today, categories);
  assert.equal(result.ok, false);
  assert.deepEqual(Object.keys(result.errors).sort(), [
    'categoryCode', 'description', 'eventDate', 'locationLabel', 'title',
  ]);
});

test('odrzuca kategorie spoza API i nierozpoznane miasto', () => {
  const result = validateReportForm({
    ...validValues, categoryCode: 'INACTIVE', locationLabel: 'Nieznane miasto',
  }, today, categories);
  assert.equal(result.ok, false);
  assert.deepEqual(Object.keys(result.errors).sort(), ['categoryCode', 'locationLabel']);
});

test('rozróżnia miasta o tej samej nazwie według województwa', () => {
  const ambiguous = validateReportForm({ ...validValues, locationLabel: 'Olsztyn' }, today, categories);
  assert.equal(ambiguous.ok, false);
  assert.match(ambiguous.errors.locationLabel, /województwo/);
  const northern = validateReportForm({ ...validValues, locationLabel: 'Olsztyn, warmińsko-mazurskie' }, today, categories);
  const southern = validateReportForm({ ...validValues, locationLabel: 'Olsztyn, slaskie' }, today, categories);
  assert.equal(northern.ok, true);
  assert.equal(southern.ok, true);
  assert.ok(northern.content.location.latitude > southern.content.location.latitude);
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

test('podczas edycji zachowuje zapisany punkt i dobiera nowy po zmianie miejsca', () => {
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
  assert.deepEqual(validateReportForm(fields, today, categories).content.location, report.editableLocation);
  const changed = validateReportForm({ ...fields, locationLabel: 'Częstochowa' }, today, categories);
  assert.deepEqual(changed.content.location, { label: 'Częstochowa', ...czestochowaPoint });
});
