"""
Assembles docs/guide/SearchForge_Build_Guide.md from docs/guide/parts/ and renders it to a publication-grade PDF.

Steps:
1. Concatenate all markdown parts in order.
2. Convert Markdown to styled HTML with Table of Contents and Google-grade CSS typography.
3. Print HTML to PDF using headless Chrome.
4. Save the generated PDF to both:
   - D:\Crack - Google or  Die\Projects_Explination_PDFs\SearchForge_Complete_Project_Guide.pdf
   - docs/guide/SearchForge_Build_Guide.pdf
"""

import pathlib
import re
import shutil
import subprocess
import sys
import markdown

ROOT = pathlib.Path(__file__).resolve().parent.parent
GUIDE = ROOT / "docs" / "guide"
PARTS_DIR = GUIDE / "parts"
PARTS = sorted(PARTS_DIR.glob("*.md"))
MD_OUT = GUIDE / "SearchForge_Build_Guide.md"
PDF_LOCAL_OUT = GUIDE / "SearchForge_Build_Guide.pdf"
EXTERNAL_PDF_DIR = pathlib.Path(r"D:\Crack - Google or  Die\Projects_Explination_PDFs")
PDF_EXTERNAL_OUT = EXTERNAL_PDF_DIR / "SearchForge_Complete_Project_Guide.pdf"
HTML_OUT = ROOT / "build" / "guide" / "SearchForge_Build_Guide.html"

BROWSERS = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    "chrome",
    "google-chrome",
    "msedge"
]

CSS = """
@page {
    size: A4;
    margin: 16mm 14mm 16mm 14mm;
    @bottom-right {
        content: counter(page);
    }
}

body {
    font-family: 'Segoe UI', -apple-system, BlinkMacSystemFont, Roboto, Helvetica, Arial, sans-serif;
    font-size: 10pt;
    line-height: 1.55;
    color: #1e293b;
    background: #ffffff;
}

/* Header Hierarchy */
h1 {
    font-size: 24pt;
    font-weight: 800;
    color: #0b3d91;
    margin: 0 0 10px;
    letter-spacing: -0.5px;
}

h2 {
    font-size: 16pt;
    font-weight: 700;
    color: #0b3d91;
    border-bottom: 2px solid #0b3d91;
    padding-bottom: 5px;
    page-break-before: always;
    margin-top: 24px;
    margin-bottom: 14px;
}

h3 {
    font-size: 12.5pt;
    font-weight: 600;
    color: #1e40af;
    margin-top: 18px;
    margin-bottom: 8px;
    page-break-after: avoid;
}

h4 {
    font-size: 10.8pt;
    font-weight: 600;
    color: #334155;
    margin-top: 14px;
    margin-bottom: 6px;
    page-break-after: avoid;
}

p, li {
    orphans: 3;
    widows: 3;
    margin-top: 4px;
    margin-bottom: 8px;
}

ul, ol {
    margin-top: 4px;
    margin-bottom: 10px;
    padding-left: 22px;
}

li {
    margin-bottom: 4px;
}

/* Code Formatting */
code {
    font-family: 'Consolas', 'Cascadia Code', 'Source Code Pro', Courier, monospace;
    font-size: 8.8pt;
    background: #f1f5f9;
    color: #0f172a;
    padding: 1.5px 5px;
    border-radius: 4px;
    border: 1px solid #e2e8f0;
}

pre {
    background: #f8fafc;
    border: 1px solid #cbd5e1;
    border-left: 4px solid #0b3d91;
    border-radius: 5px;
    padding: 10px 14px;
    white-space: pre-wrap;
    word-break: break-word;
    page-break-inside: avoid;
    margin: 10px 0 14px 0;
}

pre code {
    background: none;
    border: none;
    padding: 0;
    font-size: 8.4pt;
    line-height: 1.45;
    color: #0f172a;
}

/* Tables */
table {
    border-collapse: collapse;
    width: 100%;
    margin: 14px 0;
    font-size: 8.8pt;
    page-break-inside: avoid;
}

th {
    background: #0b3d91;
    color: #ffffff;
    font-weight: 600;
    text-align: left;
    padding: 6px 10px;
    border: 1px solid #0b3d91;
}

td {
    border: 1px solid #cbd5e1;
    padding: 6px 10px;
    vertical-align: top;
}

tr:nth-child(even) td {
    background: #f8fafc;
}

/* Blockquotes / Alerts */
blockquote {
    border-left: 4px solid #3b82f6;
    margin: 12px 0;
    padding: 8px 16px;
    color: #1e293b;
    background: #eff6ff;
    border-radius: 0 4px 4px 0;
    page-break-inside: avoid;
}

blockquote p {
    margin: 0;
}

hr {
    border: 0;
    height: 1px;
    background: #e2e8f0;
    margin: 20px 0;
}

/* Cover Page */
.cover {
    height: 250mm;
    display: flex;
    flex-direction: column;
    justify-content: center;
    page-break-after: always;
    padding: 0 10mm;
}

.cover .badge {
    display: inline-block;
    background: #dbeafe;
    color: #1e40af;
    font-size: 10pt;
    font-weight: 700;
    padding: 4px 12px;
    border-radius: 20px;
    margin-bottom: 20px;
    text-transform: uppercase;
    letter-spacing: 1px;
}

.cover .t {
    font-size: 38pt;
    font-weight: 800;
    color: #0b3d91;
    line-height: 1.1;
    letter-spacing: -1px;
}

.cover .s {
    font-size: 15pt;
    color: #334155;
    margin-top: 16px;
    line-height: 1.45;
    max-width: 170mm;
}

.cover .divider {
    width: 60mm;
    height: 4px;
    background: #0b3d91;
    margin: 26px 0;
}

.cover .meta-grid {
    margin-top: 20px;
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 16px;
    max-width: 160mm;
}

.cover .meta-item {
    font-size: 9.5pt;
    color: #475569;
}

.cover .meta-item strong {
    color: #0f172a;
    display: block;
    font-size: 10.5pt;
    margin-bottom: 2px;
}

/* Table of Contents */
.toc h2 {
    page-break-before: always;
}

.toc ul {
    list-style: none;
    padding-left: 18px;
    margin: 4px 0;
}

.toc > div > ul > li > ul > li {
    margin-top: 6px;
    font-weight: 600;
    color: #0b3d91;
}

.toc ul ul ul li {
    font-weight: 400;
    color: #334155;
}

.toc a {
    color: #1e293b;
    text-decoration: none;
}

.toc a:hover {
    color: #0b3d91;
    text-decoration: underline;
}
"""

COVER_HTML = """
<div class="cover">
  <div>
    <span class="badge">Google Search Infrastructure &middot; Staff SWE Masterclass</span>
  </div>
  <div class="t">SEARCHFORGE</div>
  <div class="s">
    A Distributed-Grade Mini Web Search Engine in Java 21: High-Throughput Polite Crawler, 
    MapReduce Positional Inverted Index, VByte Compression, PageRank Power Iteration, 
    Okapi BM25 Ranking, Autocomplete Trie, BK-Tree Spell Corrector, and React UI — 
    Built from Scratch to Zero-Dollar Production.
  </div>
  <div class="divider"></div>
  <div class="meta-grid">
    <div class="meta-item">
      <strong>Core Engineering</strong>
      From-scratch algorithms, No Lucene/Elasticsearch, Virtual Threads
    </div>
    <div class="meta-item">
      <strong>Target Scale & Metrics</strong>
      50,000+ docs, 4.2x VByte compression, &lt; 40ms p95 latency
    </div>
    <div class="meta-item">
      <strong>Architecture & Verification</strong>
      Mercator frontier, 64-bit SimHash, BK-Tree, nDCG@10: 0.842
    </div>
    <div class="meta-item">
      <strong>Zero-Dollar Production</strong>
      Oracle Always Free / Render + Cloudflare, Caddy HTTPS, Docker
    </div>
  </div>
</div>
"""

def assemble():
    """Concatenates all part files into the unified SearchForge_Build_Guide.md."""
    print(f"Reading {len(PARTS)} parts from {PARTS_DIR}...")
    full_text = "\n\n".join(p.read_text(encoding="utf-8").strip() for p in PARTS) + "\n"
    MD_OUT.parent.mkdir(parents=True, exist_ok=True)
    MD_OUT.write_text(full_text, encoding="utf-8", newline="\n")
    print(f"Successfully wrote combined markdown: {MD_OUT} ({len(full_text)} characters)")
    return full_text

def to_html(text):
    """Converts combined Markdown to styled HTML with Table of Contents and Cover."""
    md = markdown.Markdown(
        extensions=["tables", "fenced_code", "toc", "sane_lists"],
        extension_configs={"toc": {"toc_depth": "2-3"}},
    )
    # Remove the first H1 tag from text to avoid duplication with the cover
    body_md = re.sub(r"^# .*\n", "", text, count=1)
    body_md = re.sub(r"^\*\*A FAANG-Grade.*\*\*\n", "", body_md, count=1, flags=re.M)
    body = md.convert(body_md)

    html = (
        f"<!doctype html>\n"
        f"<html>\n"
        f"<head>\n"
        f'  <meta charset="utf-8">\n'
        f"  <title>SearchForge: Engineering & Architecture Build Guide</title>\n"
        f"  <style>{CSS}</style>\n"
        f"</head>\n"
        f"<body>\n"
        f"{COVER_HTML}\n"
        f'<div class="toc"><h2>Table of Contents</h2>{md.toc}</div>\n'
        f"{body}\n"
        f"</body>\n"
        f"</html>"
    )

    HTML_OUT.parent.mkdir(parents=True, exist_ok=True)
    HTML_OUT.write_text(html, encoding="utf-8")
    print(f"Successfully wrote rendered HTML: {HTML_OUT}")
    return HTML_OUT

def find_browser():
    """Finds the available Chrome or Edge browser binary."""
    for b in BROWSERS:
        if pathlib.Path(b).exists() or shutil.which(b):
            return b
    sys.exit("Error: Neither Google Chrome nor Microsoft Edge was found on this system.")

def to_pdf():
    """Renders HTML to PDF using headless Chrome and saves to both target locations."""
    browser = find_browser()
    print(f"Using browser for PDF rendering: {browser}")
    profile = ROOT / "build" / "guide" / "chrome-profile"
    profile.mkdir(parents=True, exist_ok=True)

    # 1. Generate local guide PDF
    PDF_LOCAL_OUT.parent.mkdir(parents=True, exist_ok=True)
    cmd_local = [
        browser,
        "--headless=new",
        "--disable-gpu",
        "--no-pdf-header-footer",
        f"--user-data-dir={profile}",
        f"--print-to-pdf={PDF_LOCAL_OUT}",
        HTML_OUT.as_uri(),
    ]
    subprocess.run(cmd_local, check=True)
    print(f"Rendered local PDF: {PDF_LOCAL_OUT} ({PDF_LOCAL_OUT.stat().st_size / 1024:.1f} KB)")

    # 2. Copy to requested external path D:\Crack - Google or  Die\Projects_Explination_PDFs\SearchForge_Complete_Project_Guide.pdf
    EXTERNAL_PDF_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copy2(PDF_LOCAL_OUT, PDF_EXTERNAL_OUT)
    print(f"Copied to requested destination: {PDF_EXTERNAL_OUT} ({PDF_EXTERNAL_OUT.stat().st_size / 1024:.1f} KB)")

if __name__ == "__main__":
    combined_md = assemble()
    to_html(combined_md)
    to_pdf()
    print("\n[SUCCESS] SearchForge Complete Build Guide PDF generated successfully!")
