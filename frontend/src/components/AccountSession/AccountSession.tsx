import { useEffect, useSyncExternalStore } from 'react';
import { CircleAlert, LoaderCircle, LogOut, UserRound } from 'lucide-react';
import { NavLink } from 'react-router-dom';
import { getAuthSnapshot, initializeAuth, login, logout, register, retryProfile, subscribeAuth } from '../../services/auth';
import './AccountSession.css';

export function AccountSession() {
  const session = useSyncExternalStore(subscribeAuth, getAuthSnapshot);
  useEffect(() => { void initializeAuth(); }, []);
  const pending = session.status === 'loading' || session.status === 'synchronizing';

  return (
    <section className="account-session" aria-label="Twoje konto" aria-busy={pending}>
      <NavLink className="account-session__identity" to="/moje-ogloszenia" aria-label="Twoje konto — Moje ogłoszenia">
      <span className={`account-session__avatar${pending ? ' account-session__avatar--pending' : ''}`} aria-hidden="true">
        {pending ? <LoaderCircle size={19} /> : <UserRound size={19} />}
      </span>
      <div className="account-session__details" aria-live="polite">
        {session.status === 'loading' && <span>Sprawdzanie sesji…</span>}
        {session.status === 'synchronizing' && <span>Przygotowywanie Twojego konta…</span>}
        {session.profile && (
          <>
            <strong title={session.profile.displayName}>Twoje konto · {session.profile.displayName}</strong>
            <span className="account-session__email">
              <span title={session.profile.email ?? undefined}>{session.profile.email ?? 'Brak adresu e-mail'}</span>
              {session.profile.email && !session.profile.emailVerified && (
                <span className="account-session__verification" title="Adres e-mail niepotwierdzony">
                  <CircleAlert size={13} aria-hidden="true" />
                  <span className="account-session__sr-only">Adres e-mail niepotwierdzony</span>
                </span>
              )}
            </span>
          </>
        )}
        {session.status === 'anonymous' && <strong>Twoje konto</strong>}
        {session.error && <span className="account-session__error" role="alert">{session.error}</span>}
      </div>
      </NavLink>
      <div className="account-session__actions">
        {session.status === 'error' && <button type="button" onClick={() => void retryProfile()}>Spróbuj ponownie</button>}
        {!pending && (session.signedIn
          ? <button type="button" className="account-session__logout" onClick={() => void logout()}>
              <LogOut size={16} aria-hidden="true" /> Wyloguj się
            </button>
          : session.status === 'anonymous' && <>
              <button type="button" className="account-session__secondary" onClick={() => void login()}>Zaloguj się</button>
              <button type="button" onClick={() => void register()}>Utwórz konto</button>
            </>)}
      </div>
    </section>
  );
}
