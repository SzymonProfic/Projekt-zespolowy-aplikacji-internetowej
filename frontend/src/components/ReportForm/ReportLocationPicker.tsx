import { importLibrary, setOptions } from '@googlemaps/js-api-loader';
import { useEffect, useId, useRef, useState } from 'react';
import type { ReportLocationPoint } from '../../types/report';

const mapsApiKey = import.meta.env.VITE_GOOGLE_MAPS_API_KEY?.trim();
const googleMapId = import.meta.env.VITE_GOOGLE_MAPS_MAP_ID?.trim() || 'DEMO_MAP_ID';
let mapsConfigured = false;

interface ReportLocationPickerProps {
  address: string;
  point: ReportLocationPoint | null;
  onPick: (point: ReportLocationPoint) => void;
  disabled: boolean;
}

type MapState =
  | { status: 'loading' | 'error' }
  | {
    status: 'ready';
    map: google.maps.Map;
    marker: google.maps.marker.AdvancedMarkerElement;
    addressFound: boolean;
  };

function toMapPosition({ latitude, longitude }: ReportLocationPoint): google.maps.LatLngLiteral {
  return { lat: latitude, lng: longitude };
}

function LocationMap({ address, point, initialPoint, onPick, disabled }: ReportLocationPickerProps & {
  initialPoint: ReportLocationPoint | null;
}) {
  const container = useRef<HTMLDivElement>(null);
  const [view, setView] = useState<MapState>({ status: 'loading' });

  useEffect(() => {
    const element = container.current;
    if (!element || !mapsApiKey) return;
    if (!mapsConfigured) {
      setOptions({ key: mapsApiKey, v: 'quarterly', language: 'pl', region: 'PL' });
      mapsConfigured = true;
    }

    let active = true;
    let marker: google.maps.marker.AdvancedMarkerElement | undefined;

    async function openMap(element: HTMLDivElement) {
      const [{ Map }, { AdvancedMarkerElement }, { Geocoder }] = await Promise.all([
        importLibrary('maps'),
        importLibrary('marker'),
        importLibrary('geocoding'),
      ]);
      if (!active) return;

      // Use Google's result to frame the map. The report only saves a point chosen by the user.
      const addressResult = initialPoint ? undefined : (await new Geocoder().geocode({
        address, region: 'pl', fulfillOnZeroResults: true,
      })).results[0];
      if (!active) return;

      const map = new Map(element, {
        center: initialPoint ? toMapPosition(initialPoint) : { lat: 52.1, lng: 19.4 },
        zoom: initialPoint ? 15 : 6,
        mapId: googleMapId,
        mapTypeControl: false,
        streetViewControl: false,
        clickableIcons: false,
        gestureHandling: 'cooperative',
      });
      if (addressResult) map.fitBounds(addressResult.geometry.viewport);

      marker = new AdvancedMarkerElement({ map, title: 'Miejsce zdarzenia' });
      setView({ status: 'ready', map, marker, addressFound: Boolean(initialPoint || addressResult) });
    }

    void openMap(element).catch(() => { if (active) setView({ status: 'error' }); });

    return () => {
      active = false;
      if (marker) marker.map = null;
      element.replaceChildren();
    };
  }, [address, initialPoint]);

  useEffect(() => {
    if (view.status === 'ready') view.marker.position = point ? toMapPosition(point) : null;
  }, [view, point]);

  useEffect(() => {
    if (view.status !== 'ready' || disabled) return;
    const listener = view.map.addListener('click', ({ latLng }: google.maps.MapMouseEvent) => {
      if (latLng) onPick({ latitude: latLng.lat(), longitude: latLng.lng() });
    });
    return () => listener.remove();
  }, [view, onPick, disabled]);

  function pickMapCenter() {
    const center = view.status === 'ready' ? view.map.getCenter() : undefined;
    if (center) onPick({ latitude: center.lat(), longitude: center.lng() });
  }

  return (
    <>
      {view.status === 'loading' && <p role="status">Ładowanie mapy…</p>}
      {view.status === 'error' && <p role="alert">Mapa jest niedostępna. Zamknij ją i spróbuj ponownie.</p>}
      {view.status === 'ready' && (
        <p role="status">
          {view.addressFound ? 'Kliknij na mapie, aby ustawić pinezkę.' : 'Nie znaleziono nazwy. Możesz wskazać miejsce na mapie.'}
        </p>
      )}
      <div ref={container} className="report-location__map" role="region" aria-label="Mapa miejsca zdarzenia"
        hidden={view.status === 'error'} />
      {view.status === 'ready' && (
        <button type="button" className="report-action" disabled={disabled} onClick={pickMapCenter}>
          Ustaw pinezkę na środku mapy
        </button>
      )}
    </>
  );
}

export function ReportLocationPicker({ address, point, onPick, disabled }: ReportLocationPickerProps) {
  const panelId = useId();
  const [openMap, setOpenMap] = useState<{ initialPoint: ReportLocationPoint | null } | null>(null);

  return (
    <div className="report-location">
      {mapsApiKey ? (
        <button type="button" className="report-action" disabled={disabled || address.length < 3}
          aria-expanded={openMap !== null} aria-controls={panelId}
          onClick={() => setOpenMap((current) => current ? null : { initialPoint: point })}>
          {openMap ? 'Ukryj mapę' : 'Pokaż na mapie'}
        </button>
      ) : <p>Mapa Google wymaga konfiguracji.</p>}
      {point && <p role="status">Pinezka ustawiona.</p>}
      <div id={panelId} className="report-location__panel" hidden={!openMap}>
        {openMap && <LocationMap address={address} point={point} initialPoint={openMap.initialPoint}
          onPick={onPick} disabled={disabled} />}
      </div>
    </div>
  );
}
