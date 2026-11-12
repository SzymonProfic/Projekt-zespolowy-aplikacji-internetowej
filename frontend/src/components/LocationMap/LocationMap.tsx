import { useEffect, useRef, useState } from 'react';
import * as L from 'leaflet';
import type { ReportLocationPoint } from '../../types/report';
import 'leaflet/dist/leaflet.css';
import './LocationMap.css';

interface LocationMapProps {
  point: ReportLocationPoint | null;
  label: string;
  onChange?: (point: ReportLocationPoint) => void;
  radiusMeters?: number;
}

export function LocationMap({ point, label, onChange, radiusMeters }: LocationMapProps) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const marker = useRef<L.Marker | null>(null);
  const circle = useRef<L.Circle | null>(null);
  const [tileError, setTileError] = useState(false);
  const latitude = point?.latitude;
  const longitude = point?.longitude;

  useEffect(() => {
    if (!container.current) return;
    const instance = L.map(container.current, { scrollWheelZoom: false }).setView([52.1, 19.4], 6);
    map.current = instance;
    const tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
    }).addTo(instance);
    tiles.on('tileerror', () => setTileError(true));
    const resize = new ResizeObserver(() => instance.invalidateSize());
    resize.observe(container.current);
    return () => {
      resize.disconnect();
      instance.remove();
      map.current = null;
      marker.current = null;
      circle.current = null;
    };
  }, []);

  useEffect(() => {
    const instance = map.current;
    if (!instance) return;
    if (latitude === undefined || longitude === undefined) {
      marker.current?.remove();
      marker.current = null;
      instance.setView([52.1, 19.4], 6);
      return;
    }
    const position: L.LatLngTuple = [latitude, longitude];
    if (!marker.current) {
      marker.current = L.marker(position, {
        icon: L.divIcon({ className: 'location-map__pin', iconSize: [26, 34], iconAnchor: [13, 34] }),
        alt: 'Miejsce zdarzenia',
      }).addTo(instance);
    } else marker.current.setLatLng(position);
    instance.setView(position, Math.max(instance.getZoom(), 13));
  }, [latitude, longitude]);

  useEffect(() => {
    const instance = map.current;
    if (!instance) return;
    if (latitude === undefined || longitude === undefined || !radiusMeters) {
      circle.current?.remove();
      circle.current = null;
      return;
    }
    const position: L.LatLngTuple = [latitude, longitude];
    if (!circle.current) {
      circle.current = L.circle(position, {
        radius: radiusMeters,
        color: '#176f60',
        fillOpacity: 0.12,
        weight: 2,
        interactive: false,
      }).addTo(instance);
    } else circle.current.setLatLng(position).setRadius(radiusMeters);
    instance.fitBounds(circle.current.getBounds(), { padding: [24, 24], maxZoom: 15, animate: false });
  }, [latitude, longitude, radiusMeters]);

  useEffect(() => {
    const instance = map.current;
    const pin = marker.current;
    if (!instance) return;
    const select = (event: L.LeafletMouseEvent) => {
      onChange?.({ latitude: event.latlng.lat, longitude: event.latlng.wrap().lng });
    };
    const drag = () => {
      const position = pin?.getLatLng();
      if (position) onChange?.({ latitude: position.lat, longitude: position.wrap().lng });
    };
    if (onChange) {
      instance.on('click', select);
      pin?.dragging?.enable();
      pin?.on('dragend', drag);
    } else pin?.dragging?.disable();
    return () => {
      instance.off('click', select);
      pin?.off('dragend', drag);
    };
  }, [onChange, latitude, longitude]);

  return (
    <div className="location-map">
      <div ref={container} className="location-map__canvas" role="region" aria-label={`Mapa miejsca zdarzenia: ${label || 'Polska'}`} />
      {tileError && <p className="location-map__error" role="status">Nie udało się pobrać części mapy. Sprawdź połączenie z internetem. Lokalizacja nadal jest dostępna bez podkładu mapy.</p>}
    </div>
  );
}
