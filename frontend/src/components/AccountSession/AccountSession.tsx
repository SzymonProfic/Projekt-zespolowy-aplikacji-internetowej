import { useEffect, useSyncExternalStore } from 'react';
import { getAuthSnapshot, initializeAuth, login, logout, retryProfile, subscribeAuth } from '../../services/auth';
import './AccountSession.css';

export function AccountSession() {
  const session = useSyncExternalStore(subscribeAuth, getAuthSnapshot);
  useEffect(() => { void initializeAuth(); }, []);
  const pending = session.status === 'loading' || session.status === 'synchronizing';

  return (
    <section className="account-session" aria-label="Twoje konto" aria-busy={pending}>
      <div className="account-session__details" aria-live="polite">
        {session.status === 'loading' && <span>Sprawdzanie sesji…</span>}
        {session.status === 'synchronizing' && <span>Przygotowywanie Twojego konta…</span>}
        {session.profile && (
          <>
            <strong>{session.profile.displayName}</strong>
            <span>
              {session.profile.email ?? 'Brak adresu e-mail'}
              {session.profile.email && !session.profile.emailVerified && ' · Adres niepotwierdzony'}
            </span>
          </>
        )}
        {session.status === 'anonymous' && <span>Zaloguj się, aby korzystać ze swojego konta.</span>}
        {session.error && <span role="alert">{session.error}</span>}
      </div>
      <div className="account-session__actions">
        {session.status === 'error' && <button type="button" onClick={() => void retryProfile()}>Spróbuj ponownie</button>}
        {!pending && (session.signedIn
          ? <button type="button" onClick={() => void logout()}>Wyloguj się</button>
          : session.status === 'anonymous' && <button type="button" onClick={() => void login()}>Zaloguj się</button>)}
      </div>
    </section>
  );
}
