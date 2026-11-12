package dev.searchforge.query;

import dev.searchforge.indexer.Index;

// Field-weighted Okapi BM25 (BM25F, Robertson & Zaragoza 2009) plus a PageRank prior:
//   tf~   = sum_f w_f * tf_f / (1 - b + b * len_f / avgLen_f)
//   bm25  = sum_t idf(t) * tf~ * (k1 + 1) / (tf~ + k1)
//   score = bm25 + PR_WEIGHT * ln(1 + N * pageRank)
// N * pageRank is 1 for an "average" page, so the prior only rewards above-average pages.
public final class Bm25 {

    public static final double K1 = 1.2;
    public static final double B = 0.75;
    static final double[] FIELD_WEIGHTS = {3.0, 2.0, 1.0}; // title, headings, body
    static final double PR_WEIGHT = 0.5;

    private final Index index;
    private final int n;

    public Bm25(Index index) {
        this.index = index;
        this.n = index.docCount();
    }

    public double idf(int df) {
        return Math.log(1 + (n - df + 0.5) / (df + 0.5));
    }

    // tfByField: term frequency in title, headings, body for this doc.
    public double termScore(int doc, int df, int[] tfByField) {
        double tfw = 0;
        for (int f = 0; f < 3; f++) {
            if (tfByField[f] == 0) {
                continue;
            }
            double norm = 1 - B + B * index.fieldLength(doc, f) / index.avgFieldLength(f);
            tfw += FIELD_WEIGHTS[f] * tfByField[f] / norm;
        }
        return tfw == 0 ? 0 : idf(df) * tfw * (K1 + 1) / (tfw + K1);
    }

    public double prior(int doc) {
        return PR_WEIGHT * Math.log(1 + (double) n * index.pageRank(doc));
    }
}
