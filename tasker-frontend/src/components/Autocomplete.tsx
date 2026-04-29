import { useState, useRef, useEffect } from 'react';
import type { KeyboardEvent } from 'react';

export interface AutocompleteOption {
  id: string;
  label: string;
  color?: string;
  [key: string]: any;
}

interface AutocompleteProps {
  value: string;
  onChange: (value: string) => void;
  onSelect?: (option: AutocompleteOption) => void;
  onKeyDown?: (e: KeyboardEvent<HTMLInputElement>) => void;
  options: AutocompleteOption[];
  placeholder?: string;
  autoFocus?: boolean;
  className?: string;
}

export function Autocomplete({
  value,
  onChange,
  onSelect,
  onKeyDown,
  options,
  placeholder,
  autoFocus,
  className = 'field__input',
}: AutocompleteProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [highlightedIndex, setHighlightedIndex] = useState(-1);
  const wrapperRef = useRef<HTMLDivElement>(null);

  const filteredOptions = options.filter(opt =>
    opt.label.toLowerCase().includes(value.toLowerCase())
  );

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (wrapperRef.current && !wrapperRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (isOpen) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setHighlightedIndex(prev => (prev < filteredOptions.length - 1 ? prev + 1 : prev));
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setHighlightedIndex(prev => (prev > 0 ? prev - 1 : 0));
        return;
      }
      if (e.key === 'Enter' && highlightedIndex >= 0 && highlightedIndex < filteredOptions.length) {
        e.preventDefault();
        const option = filteredOptions[highlightedIndex];
        onChange(option.label);
        onSelect?.(option);
        setIsOpen(false);
        return;
      }
      if (e.key === 'Escape') {
        setIsOpen(false);
        // Let the event bubble up if needed
      }
    }
    
    // Call user's onKeyDown after our internal handling if it wasn't intercepted
    onKeyDown?.(e);
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    onChange(e.target.value);
    setIsOpen(true);
    setHighlightedIndex(-1);
  };

  const handleSelect = (option: AutocompleteOption) => {
    onChange(option.label);
    onSelect?.(option);
    setIsOpen(false);
  };

  return (
    <div ref={wrapperRef} style={{ position: 'relative', width: '100%' }}>
      <input
        className={className}
        value={value}
        onChange={handleChange}
        onFocus={() => setIsOpen(true)}
        onKeyDown={handleKeyDown}
        placeholder={placeholder}
        autoFocus={autoFocus}
      />
      {isOpen && filteredOptions.length > 0 && value.length > 0 && (
        <ul className="autocomplete-dropdown" style={{
          position: 'absolute',
          top: '100%',
          left: 0,
          right: 0,
          zIndex: 10,
          background: 'var(--paper-surface)',
          border: '1px solid var(--paper-border)',
          borderRadius: '4px',
          boxShadow: 'var(--shadow-drawer)',
          maxHeight: '200px',
          overflowY: 'auto',
          margin: 0,
          padding: 0,
          listStyle: 'none'
        }}>
          {filteredOptions.map((opt, i) => (
            <li
              key={opt.id}
              onClick={() => handleSelect(opt)}
              onMouseEnter={() => setHighlightedIndex(i)}
              style={{
                padding: '8px 12px',
                cursor: 'pointer',
                background: i === highlightedIndex ? 'rgba(0,0,0,0.05)' : 'transparent',
                display: 'flex',
                alignItems: 'center',
                gap: '8px'
              }}
            >
              {opt.color && (
                <span style={{
                  width: '12px',
                  height: '12px',
                  borderRadius: '50%',
                  background: opt.color,
                  opacity: 0.8
                }} />
              )}
              {opt.label}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
