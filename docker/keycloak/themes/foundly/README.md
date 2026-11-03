# Motyw Foundly dla Keycloak

Motyw `foundly` rozszerza wbudowany `keycloak.v2` z Keycloak 26.8.1. Zachowuje standardowe formularze, walidację i akcje Keycloak; dodaje style aplikacji, favicon, stopkę oraz teksty w językach polskim i angielskim. Domyślny język realmu to polski.

## Uruchomienie

Z głównego katalogu repozytorium:

```bash
docker compose up -d keycloak-init
```

Compose montuje ten katalog w `/opt/keycloak/themes/foundly`. Import realmu wybiera motyw dla nowej instalacji. Inicjalizator aktualizuje też ustawienia `loginTheme`, języków i `registrationAllowed` w istniejącym realmie przez Admin REST API; nie importuje ponownie całego realmu i nie zmienia istniejących użytkowników ani ich haseł. Po poprawnym wykonaniu kontener inicjalizatora kończy pracę z kodem 0.

Frontend na `http://localhost:5173` udostępnia przyciski **Zaloguj się** i **Utwórz konto**. Drugi używa metody `register()` adaptera Keycloak, więc otwiera rejestrację w przepływie Authorization Code z PKCE S256 i wraca do aplikacji. Link do rejestracji jest też na formularzu logowania Keycloak.

## Edycja

- `login/resources/css/foundly.css` — kolory, typografia, formularze i układ mobilny.
- `login/resources/img/favicon.svg` — ikona karty przeglądarki.
- `login/messages/messages_{pl,en}.properties` — teksty i nazwa aplikacji.
- `login/footer.ftl` — stopka wspólna dla formularzy.
- `login/theme.properties` — dziedziczenie, kolejność arkuszy i języki.

Developerski Compose wyłącza cache motywów, szablonów i zasobów statycznych. Po zmianach wystarczy odświeżyć formularz; przy zmianie ustawień realmu uruchom ponownie `keycloak-init`. W konfiguracji produkcyjnej należy przywrócić cache. Przy aktualizacji Keycloak sprawdź zgodność stylów z klasami motywu nadrzędnego.

Opis rozszerzania motywów: [Keycloak Themes](https://www.keycloak.org/ui-customization/themes). Przepływ rejestracji: [Keycloak JavaScript adapter](https://www.keycloak.org/securing-apps/javascript-adapter).
