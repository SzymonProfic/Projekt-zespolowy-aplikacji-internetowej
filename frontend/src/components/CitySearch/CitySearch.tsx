import { useId, useRef, useState } from 'react';
import { findCities, type CitySuggestion } from '../../utils/cities';
import './CitySearch.css';

interface CitySearchProps {
  value: string;
  error: string;
  onChange: (value: string) => void;
  onSelect: (city: CitySuggestion) => void;
  id?: string;
  name?: string;
  label?: string;
  required?: boolean;
  disabled?: boolean;
  className?: string;
  help?: string;
}

export function CitySearch({ value, error, onChange, onSelect, id: providedId, name, label = 'Lokalizacja',
  required = false, disabled = false, className = '',
  help = 'Wybierz miasto z podpowiedzi lub wskaż punkt na mapie.',
}: CitySearchProps) {
  const generatedId = useId();
  const id = providedId ?? generatedId;
  const input = useRef<HTMLInputElement>(null);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const suggestions = findCities(value);
  const expanded = !disabled && open && value.trim().length >= 2;

  function select(city: CitySuggestion) {
    onSelect(city);
    input.current?.focus();
    setOpen(false);
  }

  return (
    <div className={`filter-field city-search ${className}`} onBlur={(event) => {
      if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
    }}>
      <label htmlFor={id}>{label}{required && <span aria-hidden="true"> *</span>}</label>
      <div className="city-search__control">
      <input ref={input} id={id} name={name} required={required} disabled={disabled}
        minLength={required ? 3 : undefined} role="combobox" autoComplete="off" value={value} maxLength={255}
        placeholder="Wybierz miasto, np. Częstochowa" aria-expanded={expanded} aria-autocomplete="list"
        aria-controls={`${id}-suggestions`} aria-activedescendant={expanded && suggestions[active] ? `${id}-option-${active}` : undefined}
        aria-invalid={Boolean(error)} aria-describedby={`${id}-help${error ? ` ${id}-error` : ''}`}
        onFocus={() => setOpen(true)}
        onChange={(event) => { onChange(event.target.value); setOpen(true); setActive(0); }}
        onKeyDown={(event) => {
          if (event.key === 'Escape') { event.preventDefault(); setOpen(false); }
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            setOpen(true);
            setActive((index) => Math.max(0, Math.min(suggestions.length - 1, index + (event.key === 'ArrowDown' ? 1 : -1))));
          }
          if (event.key === 'Enter' && expanded && suggestions[active]) {
            event.preventDefault();
            select(suggestions[active]);
          }
        }} />
      {expanded && (
        <div className="city-search__dropdown">
          <ul id={`${id}-suggestions`} role="listbox" aria-label="Miasta">
            {suggestions.map((city, index) => (
              <li key={city.label} role="presentation">
                <button id={`${id}-option-${index}`} type="button" role="option" aria-selected={active === index}
                  tabIndex={-1} onMouseDown={(event) => event.preventDefault()} onClick={() => select(city)}>
                  {city.label}
                </button>
              </li>
            ))}
          </ul>
          {suggestions.length === 0 && <p role="status">Nie znaleziono miasta. Możesz wybrać punkt na mapie.</p>}
        </div>
      )}
      </div>
      <span id={`${id}-help`} className="city-search__help">{help}</span>
      {error && <span id={`${id}-error`} className="city-search__error" role="alert">{error}</span>}
    </div>
  );
}
