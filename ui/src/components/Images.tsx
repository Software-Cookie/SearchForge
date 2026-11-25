import { useEffect, useRef, useState } from "react";
import type { ImageHit } from "../api";
import { safeHref } from "./Results";

// A row of images inside the All tab, linking to the Images tab.
export function ImageStrip({ query, images, onMore }: {
  query: string;
  images: ImageHit[];
  onMore: () => void;
}) {
  const [viewing, setViewing] = useState<ImageHit | null>(null);
  return (
    <section className="strip" aria-label={`Images for ${query}`}>
      <h3 className="strip__title">Images</h3>
      <ul className="strip__row">
        {images.slice(0, 8).map((img) => (
          <li key={img.imageUrl}>
            <Tile img={img} onOpen={() => setViewing(img)} />
          </li>
        ))}
      </ul>
      <button type="button" className="strip__more" onClick={onMore}>
        View all images <span aria-hidden="true">→</span>
      </button>
      {viewing && <Viewer img={viewing} onClose={() => setViewing(null)} />}
    </section>
  );
}

// The Images tab: a grid of tiles, a preview on click, and a "More images" button.
export function ImageGrid({ images, hasMore, loadingMore, onMore }: {
  images: ImageHit[];
  hasMore: boolean;
  loadingMore: boolean;
  onMore: () => void;
}) {
  const [viewing, setViewing] = useState<ImageHit | null>(null);
  if (images.length === 0) {
    return <p className="empty">No images found. Try a different search.</p>;
  }
  return (
    <section aria-labelledby="images-heading">
      <h2 id="images-heading" className="visually-hidden">
        Image results
      </h2>
      <ul className="grid">
        {images.map((img) => (
          <li key={img.imageUrl} className="grid__item">
            <Tile img={img} onOpen={() => setViewing(img)} caption />
          </li>
        ))}
      </ul>
      {hasMore && (
        <div className="grid__more">
          <button type="button" className="button" onClick={onMore} disabled={loadingMore}>
            {loadingMore ? "Loading…" : "More images"}
          </button>
        </div>
      )}
      {viewing && <Viewer img={viewing} onClose={() => setViewing(null)} />}
    </section>
  );
}

function Tile({ img, onOpen, caption }: { img: ImageHit; onOpen: () => void; caption?: boolean }) {
  const [broken, setBroken] = useState(false);
  const thumb = safeHref(img.thumbnailUrl);
  if (broken || !thumb) return null;
  return (
    <button type="button" className="tile" onClick={onOpen} title={img.title}>
      <span className="tile__frame">
        <img src={thumb} alt={img.title} loading="lazy" referrerPolicy="no-referrer"
          onError={() => setBroken(true)} />
      </span>
      {caption && (
        <span className="tile__caption">
          <span className="tile__site">{img.site}</span>
          <span className="tile__name">{img.title || "Untitled"}</span>
        </span>
      )}
    </button>
  );
}

// Preview dialog: the larger image, where it comes from, and a link to the page.
function Viewer({ img, onClose }: { img: ImageHit; onClose: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  const page = img.pageUrl ? safeHref(img.pageUrl) : undefined;
  const full = safeHref(img.imageUrl) ?? safeHref(img.thumbnailUrl);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    ref.current?.focus();
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="viewer" role="presentation" onClick={onClose}>
      <div className="viewer__panel" role="dialog" aria-modal="true" aria-label={img.title}
        tabIndex={-1} ref={ref} onClick={(e) => e.stopPropagation()}>
        <button type="button" className="viewer__close" aria-label="Close" onClick={onClose}>
          <svg className="icon" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M6 6l12 12M18 6 6 18" />
          </svg>
        </button>
        <div className="viewer__image">
          <img src={full} alt={img.title} referrerPolicy="no-referrer" />
        </div>
        <div className="viewer__meta">
          <span className="viewer__site">{img.site}</span>
          <p className="viewer__title">{img.title || "Untitled"}</p>
          {img.width > 0 && img.height > 0 && (
            <p className="viewer__size">{img.width} × {img.height}</p>
          )}
          {page && (
            <a className="button button--primary" href={page} rel="noopener noreferrer"
              target="_blank">
              Visit page
            </a>
          )}
        </div>
      </div>
    </div>
  );
}
