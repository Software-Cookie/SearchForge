import { readQuery, type QueryPart } from "../queryParts";

// Shows, as the user types, how the engine will read the query: which words must all
// appear, which are alternatives, which are exact phrases and which are excluded.
export function QueryReading({ query }: { query: string }) {
  const parts = readQuery(query);
  if (parts.length === 0) {
    return (
      <p className="reading reading--hint" id="query-reading">
        Use quotes for an exact phrase, OR for alternatives and a minus sign to leave a word
        out.
      </p>
    );
  }
  return (
    <p className="reading" id="query-reading" aria-live="polite">
      <span className="reading__lead">Finds pages with</span>{" "}
      {parts.map((p, i) => (
        <Part key={i} part={p} first={i === 0} />
      ))}
    </p>
  );
}

function Part({ part, first }: { part: QueryPart; first: boolean }) {
  const joiner = first ? null : part.kind === "exclude" ? " but not " : " and ";
  switch (part.kind) {
    case "word":
      return (
        <>
          {joiner}
          <span className="tok tok--word">{part.text}</span>
        </>
      );
    case "phrase":
      return (
        <>
          {joiner}
          <span className="tok tok--phrase">“{part.text}”</span>
        </>
      );
    case "either":
      return (
        <>
          {joiner}
          {part.options.map((o, i) => (
            <span key={i}>
              {i > 0 && <span className="reading__or"> or </span>}
              <span className="tok tok--either">{o}</span>
            </span>
          ))}
        </>
      );
    case "exclude":
      return (
        <>
          {first ? "everything but " : joiner}
          <span className="tok tok--exclude">{part.text}</span>
        </>
      );
  }
}
