// Reads a query the same way the engine's ParsedQuery does, so the page can show how a
// query will be interpreted while it is being typed. (Stemming happens on the server.)

export type QueryPart =
  | { kind: "word"; text: string }
  | { kind: "phrase"; text: string }
  | { kind: "either"; options: string[] }
  | { kind: "exclude"; text: string };

const TOKEN = /-?"[^"]*"?|\S+/g;

function clean(raw: string): string {
  return raw.replace(/"/g, " ").trim().replace(/\s+/g, " ");
}

export function readQuery(query: string): QueryPart[] {
  const parts: QueryPart[] = [];
  let pendingOr = false;
  for (const raw of query.match(TOKEN) ?? []) {
    if (raw === "OR") {
      pendingOr = parts.length > 0 && parts[parts.length - 1].kind !== "exclude";
      continue;
    }
    const negative = raw.startsWith("-") && raw.length > 1;
    const body = negative ? raw.slice(1) : raw;
    const text = clean(body);
    if (!text) continue;
    if (negative) {
      parts.push({ kind: "exclude", text });
      pendingOr = false;
      continue;
    }
    const isPhrase = body.startsWith('"') && text.includes(" ");
    if (pendingOr) {
      const prev = parts.pop()!;
      const prevText = prev.kind === "either" ? prev.options : [partText(prev)];
      parts.push({ kind: "either", options: [...prevText, text] });
      pendingOr = false;
    } else {
      parts.push(isPhrase ? { kind: "phrase", text } : { kind: "word", text });
    }
  }
  return parts;
}

function partText(p: QueryPart): string {
  return p.kind === "either" ? p.options.join(" or ") : p.text;
}
