// SearchForge brand. Large: the full logo (mark + wordmark) above the home search box, with a
// light-text variant for dark mode. Small: the mark plus a text wordmark for the results header.
export function Logo({ size }: { size: "large" | "small" }) {
  if (size === "large") {
    return (
      <picture className="logo logo--large">
        <source srcSet="/logo-dark.png" media="(prefers-color-scheme: dark)" />
        <img src="/logo.png" alt="SearchForge" width={436} height={300} />
      </picture>
    );
  }
  return (
    <span className="logo logo--small">
      <img className="logo__mark" src="/logo-mark.png" alt="" width={35} height={32} />
      <span className="logo__word">
        Search <span className="logo__forge">Forge</span>
      </span>
    </span>
  );
}
