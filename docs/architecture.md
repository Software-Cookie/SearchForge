# SearchForge Architecture Specification

## 1. System Overview

SearchForge is a single-node web search engine built in Java 21 and React/TypeScript. It implements the foundational information retrieval and web-scale crawling algorithms pioneered by Google Search infrastructure—written entirely from first principles without relying on third-party search libraries (such as Apache Lucene, Elasticsearch, or Solr).

---

## 2. Mermaid Architecture Diagrams

### 2.1 End-to-End System Flow

```mermaid
flowchart TD
    subgraph CrawlerSubsystem [Crawler Subsystem]
        Seed[Seed URLs] --> FrontQ[Mercator Front Queues - Priority]
        FrontQ --> BackQ[Mercator Back Queues - Host Politeness]
        BackQ --> Scheduler[Politeness Min-Heap Scheduler]
        Scheduler --> Fetcher[Java 21 Virtual Thread Fetcher]
        Fetcher --> Robots[RFC 9309 Robots Parser & Cache]
        Fetcher --> Net[(Internet Web Hosts)]
        Fetcher --> Dedupe[URL Normalizer & MurmurHash3 Bloom Filter]
        Dedupe --> SimHash[64-bit SimHash Near-Dup Detector]
        SimHash --> PageStore[(Append-only Page Store pages.bin)]
    end

    subgraph IndexerSubsystem [Indexer Subsystem]
        PageStore --> Tokenizer[Unicode Tokenizer & Stopword Filter]
        Tokenizer --> Stemmer[Porter Stemmer From Scratch]
        Stemmer --> PosPosting[In-Memory Positional Postings Buffer]
        PosPosting -->|RAM Limit 128MB| ExtSort[External Merge Sort K-Way Run Merge]
        ExtSort --> Segments[Immutable Compressed Index Segments]
        Segments --> Codecs[D-Gaps Delta & Variable-Byte Compression]
        Segments --> TermDict[Sorted Term Dictionary & Offsets]
    end

    subgraph RankingSubsystem [Ranking & Query Subsystem]
        PageStore --> LinkGraph[Directed Web Link Graph]
        LinkGraph --> PageRank[PageRank Power Iteration d=0.85]
        PageRank --> PRVector[Converged PageRank Vector]

        UserQuery[User Query] --> QParser[Query Parser: AND, OR, NOT, Quotes]
        QParser --> SkipIntersect[Skip Pointers & Galloping Intersection]
        SkipIntersect --> BM25[Field-Boosted Okapi BM25 Scorer]
        BM25 & PRVector --> HybridRanker[Hybrid Ranker: BM25 + ln PR + Freshness]
        HybridRanker --> TopK[Bounded Min-Heap Top-K]
        TopK --> Snippet[Sliding Window Highlighted Snippets]
    end

    subgraph ApiUiSubsystem [API & User Interface]
        User[Browser Client] <--> ReactUI[React 19 + Vite + TypeScript UI]
        ReactUI <--> AutoTrie[Autocomplete Trie < 1.5ms]
        ReactUI <--> BKTree[BK-Tree Levenshtein Spell Check]
        ReactUI <--> SpringAPI[Spring Boot 4 REST API /api/search]
        SpringAPI <--> LocalCache[(Caffeine Query Cache & Per-IP Token Bucket)]
        SpringAPI <--> Snippet
    end
```

### 2.2 Mercator URL Frontier Queue Model

```mermaid
flowchart LR
    URL[Discovered URLs] --> FQ0[Front Queue 0: High Priority]
    URL --> FQ1[Front Queue 1: Medium Priority]
    URL --> FQ2[Front Queue 2: Low Priority]

    FQ0 & FQ1 & FQ2 -->|Biased Round-Robin| BQ1[Back Queue 1: wikipedia.org]
    FQ0 & FQ1 & FQ2 -->|Biased Round-Robin| BQ2[Back Queue 2: docs.oracle.com]
    FQ0 & FQ1 & FQ2 -->|Biased Round-Robin| BQ3[Back Queue 3: github.com]

    BQ1 & BQ2 & BQ3 -->|Ready Queue State| Heap[Politeness Min-Heap: Next Fetch Time >= 2000ms]
    Heap -->|Dispatch Next URL| Workers[Virtual Thread Workers]
    Workers -->|Update Delay + 2000ms| Heap
```

### 2.3 MapReduce Batch Index Construction

```mermaid
flowchart TD
    D1[Document 1] & D2[Document 2] & D3[Document 3] --> Mapper[Map Phase: Extract Term -> DocId, Position]
    Mapper --> RAM[In-Memory Postings Buffer: 128 MB]
    RAM -->|Buffer Saturated| Flush[Sort Postings & Spill Disk Run]
    Flush --> Run1[run_001.tmp]
    Flush --> Run2[run_002.tmp]
    Flush --> RunK[run_00K.tmp]

    Run1 & Run2 & RunK --> Merge[Shuffle / External Merge Sort: K-Way Min-Heap]
    Merge --> Reducer[Reduce Phase: Consolidate Postings per Term]
    Reducer --> VByte[VByte & Delta Compression]
    VByte --> SegDict[segment_0.dict: Term Vocabulary & Byte Offsets]
    VByte --> SegPost[segment_0.postings: Compressed DocIds & TFs]
    VByte --> SegPos[segment_0.positions: Compressed Positional Offsets]
```

---

## 3. Module Boundaries & Responsibilities

| Module | Purpose & Core Contents | External Dependencies |
|---|---|---|
| `sf-core` | Page store, codecs (VByte, Delta), Bloom filter, SimHash, MurmurHash3, URL normalizer, PageRank | SLF4J API only |
| `sf-crawler` | Mercator URL frontier, politeness scheduler, robots.txt parser, virtual thread HTTP fetcher, checkpointing | jsoup (DOM traversal), `sf-core` |
| `sf-indexer` | Unicode tokenizer, stopword filter, Porter stemmer, positional inverted index, external merge sort, index writer/reader, Markdown importer | `sf-core` |
| `sf-query` | Query parser, galloping intersection, BM25F + PageRank prior, top-K heap, autocomplete trie, BK-tree, snippets, relevance metrics | `sf-core`, `sf-indexer` |
| `sf-api` | Spring Boot 4 REST controller, Caffeine cache, per-IP token bucket, security headers, Micrometer metrics | Spring Boot, Caffeine, `sf-query`, `sf-indexer` |
| `sf-bench` | JMH microbenchmarks, 30-query relevance evaluation runner | JMH, `sf-core`, `sf-query` |
| `ui` | React 19, Vite, TypeScript search frontend, plain CSS design tokens | Node 24, Vite |

---

## 4. As built (v1, November 2026)

- PageRank is computed at index time (in `sf-core`, called by the indexer) and stored per
  document in `norms.bin`; the query side only reads it.
- Posting lists are decoded lazily (docIds, then term frequencies, then positions) and
  intersected with galloping search; explicit skip pointers are not stored.
- The frontier keeps one priority queue per host (shallow pages first) plus a min-heap of
  host ready times; it does not have separate Mercator front queues.
- Deployment: one Docker image (UI + API + index) on Render's free plan. See ADR-001.
