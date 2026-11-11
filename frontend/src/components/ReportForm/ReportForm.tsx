import { useEffect, useId, useRef, useState, useSyncExternalStore, type ChangeEvent, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { getAccessToken, getAuthSnapshot, login, subscribeAuth } from '../../services/auth';
import { createReport, getCategories, ReportRequestError } from '../../services/reports';
import type { Category } from '../../types/report';
import { todayInWarsaw, validateReportForm, type ReportFormErrors, type ReportFormField, type ReportFormValues } from '../../utils/reportForm';
import './ReportForm.css';

const emptyValues: ReportFormValues = {
  type: 'lost',
  title: '',
  categoryCode: '',
  description: '',
  color: '',
  eventDate: '',
  locationLabel: '',
  latitude: '',
  longitude: '',
};

type CategoriesState = { status: 'loading' } | { status: 'ready'; items: Category[] } | { status: 'error' };

function Field({ id, label, required, error, children }: {
  id: string;
  label: string;
  required?: boolean;
  error?: string;
  children: ReactNode;
}) {
  return (
    <div className="report-form__field">
      <label htmlFor={id}>{label}{required && <span aria-hidden="true"> *</span>}</label>
      {children}
      {error && <p id={`${id}-error`} className="report-form__field-error">{error}</p>}
    </div>
  );
}

function saveErrorMessage(error: unknown) {
  if (!(error instanceof ReportRequestError)) return 'Nie udało się połączyć z serwerem. Spróbuj ponownie.';
  if (error.status === 400) return 'Serwer odrzucił zgłoszenie. Sprawdź wprowadzone dane i spróbuj ponownie.';
  if (error.status === 401) return 'Zaloguj się ponownie, aby zapisać zgłoszenie.';
  if (error.status === 403) return 'Nie masz uprawnień do zapisania tego zgłoszenia.';
  return 'Nie udało się zapisać zgłoszenia. Spróbuj ponownie.';
}

export function ReportForm() {
  const navigate = useNavigate();
  const formId = useId();
  const session = useSyncExternalStore(subscribeAuth, getAuthSnapshot);
  const [values, setValues] = useState(emptyValues);
  const [categories, setCategories] = useState<CategoriesState>({ status: 'loading' });
  const [categoryAttempt, setCategoryAttempt] = useState(0);
  const [errors, setErrors] = useState<ReportFormErrors>({});
  const [submitError, setSubmitError] = useState('');
  const [saving, setSaving] = useState(false);
  const activeSave = useRef<AbortController | null>(null);
  const today = todayInWarsaw();

  useEffect(() => {
    const controller = new AbortController();
    void getCategories(controller.signal)
      .then((items) => { if (!controller.signal.aborted) setCategories({ status: 'ready', items }); })
      .catch(() => { if (!controller.signal.aborted) setCategories({ status: 'error' }); });
    return () => controller.abort();
  }, [categoryAttempt]);

  useEffect(() => () => activeSave.current?.abort(), []);

  const fieldId = (name: ReportFormField) => `${formId}-${name}`;

  function fieldProps(name: ReportFormField) {
    return {
      id: fieldId(name),
      name,
      value: values[name],
      'aria-invalid': errors[name] ? true : undefined,
      'aria-describedby': errors[name] ? `${fieldId(name)}-error` : undefined,
      onChange: (event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => {
        const value = event.target.value;
        setValues((previous) => ({ ...previous, [name]: value }));
        if (errors[name]) setErrors((previous) => ({ ...previous, [name]: undefined }));
      },
    };
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (activeSave.current || !session.signedIn || categories.status !== 'ready') return;
    const validation = validateReportForm(values, todayInWarsaw(), categories.items.map(({ code }) => code));
    setSubmitError('');

    if (!validation.ok) {
      setErrors(validation.errors);
      const firstField = event.currentTarget.elements.namedItem(Object.keys(validation.errors)[0]);
      if (firstField instanceof HTMLElement) firstField.focus();
      return;
    }

    setErrors({});
    const controller = new AbortController();
    activeSave.current = controller;
    setSaving(true);
    try {
      const accessToken = await getAccessToken();
      if (controller.signal.aborted) return;
      if (!accessToken) throw new ReportRequestError(401, 'Brak aktywnej sesji');
      const saved = await createReport({ type: values.type, ...validation.content }, accessToken, controller.signal);
      if (!controller.signal.aborted) {
        navigate(`/ogloszenia/${saved.id}`, { replace: true, state: { reportSaved: 'created' } });
      }
    } catch (error) {
      if (!controller.signal.aborted) setSubmitError(saveErrorMessage(error));
    } finally {
      activeSave.current = null;
      if (!controller.signal.aborted) setSaving(false);
    }
  }

  return (
    <>
      <header className="report-form__heading">
        <p className="foundly-eyebrow">KAŻDE OGŁOSZENIE TO NOWA SZANSA</p>
        <h1>Pomóż rzeczom wrócić do domu.</h1>
        <p className="report-page__muted">Kilka szczegółów może zrobić wielką różnicę.</p>
      </header>

      <form className="report-panel report-form" noValidate aria-busy={saving} onSubmit={(event) => void submit(event)}>
        <p className="report-page__muted">Pola oznaczone gwiazdką są wymagane.</p>
        {!session.signedIn && (
          <div className="report-notice" role="status">
            {session.status === 'loading' ? 'Sprawdzanie sesji…' : (
              <>
                <span>Zaloguj się, aby opublikować zgłoszenie.</span>
                <button className="report-action" type="button" onClick={() => void login()}>Zaloguj się</button>
              </>
            )}
          </div>
        )}
        {categories.status === 'error' && (
          <div className="report-notice report-notice--error" role="alert">
            <span>Nie udało się pobrać kategorii.</span>
            <button className="report-action" type="button" onClick={() => {
              setCategories({ status: 'loading' });
              setCategoryAttempt((attempt) => attempt + 1);
            }}>Ponów pobieranie kategorii</button>
          </div>
        )}

        <fieldset className="report-form__fields" disabled={saving || !session.signedIn}>
          <fieldset className="report-form__type">
            <legend>Rodzaj zgłoszenia</legend>
            <div className="report-form__segmented">
              <label className="report-form__choice">
                <input type="radio" name="type" value="lost" checked={values.type === 'lost'}
                  onChange={() => setValues((previous) => ({ ...previous, type: 'lost' }))} />
                <span>Zgubiłem przedmiot</span>
              </label>
              <label className="report-form__choice">
                <input type="radio" name="type" value="found" checked={values.type === 'found'}
                  onChange={() => setValues((previous) => ({ ...previous, type: 'found' }))} />
                <span>Znalazłem przedmiot</span>
              </label>
            </div>
          </fieldset>

          <Field id={fieldId('title')} label="Nazwa przedmiotu" required error={errors.title}>
            <input {...fieldProps('title')} required minLength={3} maxLength={150} placeholder="np. Słuchawki Apple AirPods Pro" />
          </Field>

          <div className="report-form__grid">
            <Field id={fieldId('categoryCode')} label="Kategoria" required error={errors.categoryCode}>
              <select {...fieldProps('categoryCode')} required disabled={categories.status !== 'ready'}>
                <option value="">{categories.status === 'loading' ? 'Ładowanie kategorii…' : 'Wybierz kategorię'}</option>
                {categories.status === 'ready' && categories.items.map((category) => (
                  <option key={category.code} value={category.code}>{category.name}</option>
                ))}
              </select>
            </Field>
            <Field id={fieldId('color')} label="Kolor (opcjonalnie)" error={errors.color}>
              <input {...fieldProps('color')} maxLength={80} placeholder="np. Biały" />
            </Field>
          </div>

          <Field id={fieldId('description')} label="Opis przedmiotu" required error={errors.description}>
            <textarea {...fieldProps('description')} required minLength={10} maxLength={5000} rows={4}
              placeholder="Opisz przedmiot i okoliczności zdarzenia." />
          </Field>

          <div className="report-form__grid">
            <Field id={fieldId('locationLabel')} label="Miejsce zdarzenia" required error={errors.locationLabel}>
              <input {...fieldProps('locationLabel')} required minLength={3} maxLength={255} placeholder="Miasto, dzielnica lub ulica" />
            </Field>
            <Field id={fieldId('eventDate')} label="Data zdarzenia" required error={errors.eventDate}>
              <input {...fieldProps('eventDate')} type="date" required max={today} />
            </Field>
          </div>

          <fieldset className="report-form__location">
            <legend>Współrzędne miejsca zdarzenia</legend>
            <p className="report-page__muted">Pomagają znaleźć zgłoszenie w okolicy. Dokładne współrzędne widzi tylko autor.</p>
            <div className="report-form__grid">
              <Field id={fieldId('latitude')} label="Szerokość geograficzna" required error={errors.latitude}>
                <input {...fieldProps('latitude')} inputMode="decimal" required placeholder="np. 52,2297" />
              </Field>
              <Field id={fieldId('longitude')} label="Długość geograficzna" required error={errors.longitude}>
                <input {...fieldProps('longitude')} inputMode="decimal" required placeholder="np. 21,0122" />
              </Field>
            </div>
          </fieldset>
        </fieldset>

        {Object.values(errors).some(Boolean) && (
          <p className="report-notice report-notice--error" role="alert">Popraw zaznaczone pola przed zapisaniem.</p>
        )}
        {submitError && <p className="report-notice report-notice--error" role="alert">{submitError}</p>}

        <div className="report-form__actions">
          {!saving && <Link className="report-action" to="/">Anuluj</Link>}
          <button className="report-action report-action--primary" type="submit"
            disabled={saving || !session.signedIn || categories.status !== 'ready'}>
            {saving ? 'Publikowanie…' : 'Opublikuj zgłoszenie'}
          </button>
        </div>
      </form>
    </>
  );
}
