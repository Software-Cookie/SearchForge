package dev.searchforge.api.websearch;

import dev.searchforge.query.Snippets.Segment;

import java.util.List;

// One web result. favicon and thumbnail may be null; snippet is plain-text segments
// (hit = a query word), so the UI never has to render upstream HTML.
public record WebHit(String url, String title, String site, String favicon, String thumbnail,
                     String age, List<Segment> snippet) {
}
