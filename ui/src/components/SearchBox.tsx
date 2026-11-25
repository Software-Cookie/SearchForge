import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { MAX_QUERY_LENGTH, suggest } from "../api";

type Props = {
  initial: string;
  onSearch: (q: string) => void;
  size: "large" | "compact";
  autoFocus?: boolean;
  onChange?: (q: string) => void;
  onImageSearch?: (q: string) => void; // large size only: shows the two home buttons
};

export function SearchIcon() {
  return (
    <svg className="icon" viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="10.5" cy="10.5" r="6.5" />
      <path d="M15.5 15.5 21 21" />
    </svg>
  );
}

// Search field with autocomplete, following the ARIA 1.2 combobox pattern:
// arrow keys move through suggestions, Enter picks one, Escape closes the list.
export function SearchBox({ initial, onSearch, size, autoFocus, onChange, onImageSearch }: Props) {
  const [value, setValue] = useState(initial);
  const [items, setItems] = useState<string[]>([]);
  const [active, setActive] = useState(-1);
  const [open, setOpen] = useState(false);
  const listId = useId();
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => setValue(initial), [initial]);

  useEffect(() => {
    if (!open || value.trim().length < 2) {
      setItems([]);
      return;
    }
    const ctrl = new AbortController();
    const timer = window.setTimeout(() => {
      suggest(value, ctrl.signal)
        .then((s) => {
          setItems(s.filter((x) => x !== value.toLowerCase()).slice(0, 8));
          setActive(-1);
        })
        .catch(() => setItems([])); // suggestions are optional; stay quiet on errors
    }, 150);
    return () => {
      ctrl.abort();
      window.clearTimeout(timer);
    };
  }, [value, open]);

  function update(v: string) {
    setValue(v);
    setOpen(true);
    onChange?.(v);
  }

  function submit(q: string) {
    const query = q.trim();
    setOpen(false);
    setItems([]);
    inputRef.current?.blur();
    if (query) onSearch(query);
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    submit(active >= 0 ? items[active] : value);
  }

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === "Escape") {
      setOpen(false);
      setItems([]);
      return;
    }
    if (!items.length) return;
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActive((a) => (a + 1) % items.length);
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive((a) => (a <= 0 ? items.length - 1 : a - 1));
    }
  }

  const showList = open && items.length > 0;
  return (
    <form
      className={`search search--${size}${showList ? " is-open" : ""}`}
      role="search"
      onSubmit={onSubmit}
    >
      <label className="visually-hidden" htmlFor={`${listId}-input`}>
        Search
      </label>
      <div className="search__field">
        <SearchIcon />
        <input
          id={`${listId}-input`}
          ref={inputRef}
          type="text"
          inputMode="search"
          enterKeyHint="search"
          value={value}
          maxLength={MAX_QUERY_LENGTH}
          autoFocus={autoFocus}
          autoComplete="off"
          spellCheck={false}
          role="combobox"
          aria-expanded={showList}
          aria-controls={`${listId}-list`}
          aria-autocomplete="list"
          aria-activedescendant={active >= 0 ? `${listId}-opt-${active}` : undefined}
          onChange={(e) => update(e.target.value)}
          onKeyDown={onKeyDown}
          onBlur={() => window.setTimeout(() => setOpen(false), 120)}
          onFocus={() => setOpen(true)}
        />
        {value && (
          <button
            type="button"
            className="search__clear"
            aria-label="Clear"
            onClick={() => {
              update("");
              inputRef.current?.focus();
            }}
          >
            <svg className="icon" viewBox="0 0 24 24" aria-hidden="true">
              <path d="M6 6l12 12M18 6 6 18" />
            </svg>
          </button>
        )}
        {size === "compact" && (
          <button type="submit" className="search__go" aria-label="Search">
            <SearchIcon />
          </button>
        )}
      </div>
      <ul className="suggest" id={`${listId}-list`} role="listbox" aria-label="Suggestions"
        hidden={!showList}>
        {items.map((s, i) => (
          <li
            key={s}
            id={`${listId}-opt-${i}`}
            role="option"
            aria-selected={i === active}
            className={i === active ? "suggest__item is-active" : "suggest__item"}
            onMouseDown={(e) => {
              e.preventDefault();
              submit(s);
            }}
          >
            <SearchIcon />
            <span>
              <SuggestionText typed={value} full={s} />
            </span>
          </li>
        ))}
      </ul>
      {size === "large" && (
        <div className="search__buttons">
          <button type="submit" className="button">SearchForge Search</button>
          {onImageSearch && (
            <button type="button" className="button" onClick={() => {
              const query = value.trim();
              if (query) onImageSearch(query);
              else inputRef.current?.focus();
            }}>
              Image Search
            </button>
          )}
        </div>
      )}
    </form>
  );
}

// Typed part in normal weight, the completion in bold (what the user would add).
function SuggestionText({ typed, full }: { typed: string; full: string }) {
  const t = typed.toLowerCase();
  if (full.startsWith(t)) {
    return (
      <>
        {full.slice(0, t.length)}
        <strong>{full.slice(t.length)}</strong>
      </>
    );
  }
  return <>{full}</>;
}
