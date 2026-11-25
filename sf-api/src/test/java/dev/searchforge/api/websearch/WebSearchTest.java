package dev.searchforge.api.websearch;

import dev.searchforge.query.Snippets.Segment;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static dev.searchforge.api.websearch.StubWeb.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSearchTest {

    static final String WIKI = json("{'continue':{'gsroffset':5},'query':{'searchinfo':"
            + "{'suggestion':'house'},'pages':["
            + "{'index':2,'title':'Houseboat','fullurl':'https://en.wikipedia.org/wiki/Houseboat',"
            + "'extract':'A houseboat floats.'},"
            + "{'index':1,'title':'House','fullurl':'https://en.wikipedia.org/wiki/House',"
            + "'extract':'A house is a building.','thumbnail':{'source':'https://up.wiki/h.jpg'}},"
            + "{'index':3,'title':'Bad','fullurl':'javascript:x'}]}}");
    static final String HN = json("{'nbPages':3,'hits':["
            + "{'title':'My <em>house</em> build','url':'https://blog.example/house',"
            + "'points':42,'created_at':'2024-05-01T10:00:00Z'},"
            + "{'title':'No url','url':null},"
            + "{'title':'Ask','url':'https://www.ask.example/x','story_text':'<p>my house</p>'}]}");
    static final String OPENVERSE = json("{'page_count':5,'results':["
            + "{'title':'house','url':'https://live.flickr/1.jpg','thumbnail':'https://ov/1/thumb',"
            + "'foreign_landing_url':'https://www.flickr.com/p/1','width':800,'height':600},"
            + "{'title':'no url'}]}");
    static final String COMMONS = json("{'query':{'pages':[{'index':1,"
            + "'title':'File:Old house.jpg','imageinfo':[{'url':'https://upload.wiki/o.jpg',"
            + "'thumburl':'https://upload.wiki/o-400.jpg','descriptionurl':'https://commons/o',"
            + "'width':1000,'height':700}]},{'index':2,'title':'File:x.png','imageinfo':[{}]}]}}");

    StubWeb stub;

    @BeforeEach
    void start() throws IOException {
        stub = new StubWeb();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void highlightsParseHtmlAndPlainTextSafely() {
        assertThat(Highlights.fromHtml("A <strong>house</strong> &amp; <script>x</script>"
                + "<span class=\"searchmatch\">home</span>"))
                .containsExactly(new Segment("A ", false), new Segment("house", true),
                        new Segment(" & ", false), new Segment("home", true));
        assertThat(Highlights.fromHtml(null)).isEmpty();
        assertThat(Highlights.fromPlain("Big Houses and a house.", "house"))
                .containsExactly(new Segment("Big ", false), new Segment("Houses", true),
                        new Segment(" and a ", false), new Segment("house", true),
                        new Segment(".", false));
        assertThat(Highlights.fromPlain("text", "a")).containsExactly(new Segment("text", false));
        assertThat(Highlights.fromPlain(" ", "a")).isEmpty();
        List<Segment> longOne = Highlights.fromPlain("word ".repeat(200), "zzz");
        assertThat(longOne.getLast().text()).endsWith("…");
        assertThat(longOne.stream().mapToInt(s -> s.text().length()).sum())
                .isLessThanOrEqualTo(Highlights.MAX_CHARS + 2);
        assertThat(Highlights.plain("<b>Tom</b> &amp; Jerry")).isEqualTo("Tom & Jerry");
        assertThat(Highlights.plain(null)).isEmpty();
    }

    @Test
    void httpHelpersOnlyPassSafeUrls() {
        assertThat(Http.safeUrl("javascript:alert(1)")).isNull();
        assertThat(Http.safeUrl(null)).isNull();
        assertThat(Http.safeUrl("http:///go/v1/abc")).isNull();
        assertThat(Http.safeUrl("https://bad host/")).isNull();
        assertThat(Http.safeUrl("https://img.example/a|b.jpg")).isEqualTo("https://img.example/a|b.jpg");
        assertThat(Http.safeUrl("http://127.0.0.1:8080")).isEqualTo("http://127.0.0.1:8080");
        assertThat(Http.safeUrl("//cdn.example/a.png")).isEqualTo("https://cdn.example/a.png");
        assertThat(Http.host("https://www.example.com/a")).isEqualTo("example.com");
        assertThat(Http.host("not a url at all")).isEmpty();
        assertThat(Http.host(null)).isEmpty();
        Http http = new Http(stub.props("open"));
        stub.fail("/down", 500).on("/bad", "{not json");
        assertThatThrownBy(() -> http.getJson(stub.url() + "/down"))
                .isInstanceOf(UpstreamException.class).hasMessageContaining("HTTP 500");
        assertThatThrownBy(() -> http.getJson(stub.url() + "/bad"))
                .isInstanceOf(UpstreamException.class);
        assertThatThrownBy(() -> http.getJson("http://127.0.0.1:1/x"))
                .isInstanceOf(UpstreamException.class);
    }

    @Test
    void propertiesPickTheProvider() {
        WebSearchProperties none = new WebSearchProperties(null, null, null, 0, -1, null,
                null, null, null, null, null);
        assertThat(none.effectiveProvider()).isEqualTo("open");
        assertThat(none.timeoutMs()).isEqualTo(5000);
        assertThat(none.cacheSize()).isZero();
        assertThat(none.wikipediaUrl()).isEqualTo("https://en.wikipedia.org");
        assertThat(new WebSearchProperties("AUTO", "", "http://sx/", 1, 1, "ua", null, null,
                null, null, null).effectiveProvider()).isEqualTo("searxng");
        assertThat(new WebSearchProperties("auto", "k", "http://sx", 1, 1, "ua", null, null,
                null, null, null).effectiveProvider()).isEqualTo("brave");
        assertThat(new WebSearchProperties("open", "k", "", 1, 1, "ua", null, null,
                null, null, null).effectiveProvider()).isEqualTo("open");
    }

    @Test
    void braveParsesWebAndImages() {
        stub.on("/brave/res/v1/web/search", json("{'query':{'altered':'house',"
                + "'more_results_available':true},'web':{'results':["
                + "{'title':'Dream <strong>House</strong>','url':'https://www.houzz.com/a',"
                + "'description':'Ideas for your <strong>house</strong>','age':'2 days ago',"
                + "'profile':{'name':'Houzz'},'meta_url':{'favicon':'https://imgs/f.png'},"
                + "'thumbnail':{'src':'https://imgs/t.jpg'}},"
                + "{'title':'x','url':'ftp://x'},{'title':'Plain','url':'https://plain.example/'}]}}"));
        stub.on("/brave/res/v1/images/search", json("{'results':["
                + "{'title':'A house','url':'https://site.example/p','source':'site.example',"
                + "'thumbnail':{'src':'https://imgs/1.jpg'},'properties':{'url':'https://full/1.jpg',"
                + "'width':640,'height':480}},{'title':'no thumb'},"
                + "{'title':'B','url':'https://www.b.example/p','thumbnail':{'src':'https://imgs/2.jpg'}}]}"));
        BraveProvider brave = new BraveProvider(new Http(stub.props("brave")), stub.props("brave"));
        assertThat(brave.name()).isEqualTo("brave");

        WebPage web = brave.web("hosue", 2);
        assertThat(web.hasMore()).isTrue();
        assertThat(web.didYouMean()).isEqualTo("house");
        assertThat(web.hits()).hasSize(2);
        WebHit first = web.hits().getFirst();
        assertThat(first.title()).isEqualTo("Dream House");
        assertThat(first.site()).isEqualTo("Houzz");
        assertThat(first.favicon()).isEqualTo("https://imgs/f.png");
        assertThat(first.snippet()).contains(new Segment("house", true));
        assertThat(web.hits().get(1).site()).isEqualTo("plain.example");
        assertThat(stub.requests).anyMatch(r -> r.contains("offset=1"));
        assertThat(stub.tokens).contains("test-key");
        assertThat(brave.web("house", 1).didYouMean()).isNull();

        ImagePage images = brave.images("house", 1);
        assertThat(images.images()).hasSize(2);
        assertThat(images.images().getFirst().imageUrl()).isEqualTo("https://full/1.jpg");
        assertThat(images.images().get(1).imageUrl()).isEqualTo("https://imgs/2.jpg");
        assertThat(images.images().get(1).site()).isEqualTo("b.example");
        assertThat(images.hasMore()).isFalse();
        assertThat(brave.images("house", 2).images()).isEmpty();
    }

    @Test
    void searxngParsesWebAndImages() {
        stub.on("/sx/search?q=house&format=json&pageno=1&categories=general", json(
                "{'corrections':['houses'],'results':[{'url':'https://www.bob.example/house',"
                        + "'title':'Bob builds a house','content':'Our new house',"
                        + "'publishedDate':'2025-01-02T00:00:00','thumbnail':'//cdn/t.jpg'},"
                        + "{'url':'mailto:x'}]}"));
        stub.on("/sx/search?q=house&format=json&pageno=1&categories=images", json(
                "{'results':[{'img_src':'https://i/1.jpg','thumbnail_src':'https://i/1t.jpg',"
                        + "'url':'https://pics.example/1','title':'Cabin','resolution':'1920 x 1080'},"
                        + "{'img_src':'https://i/2.jpg','resolution':'big'},{'title':'none'}]}"));
        stub.on("/sx/search?q=empty", json("{'results':[]}"));
        SearxngProvider sx = new SearxngProvider(new Http(stub.props("searxng")),
                stub.props("searxng"));
        assertThat(sx.name()).isEqualTo("searxng");

        WebPage web = sx.web("house", 1);
        assertThat(web.hits()).singleElement().satisfies(h -> {
            assertThat(h.site()).isEqualTo("bob.example");
            assertThat(h.age()).isEqualTo("2025-01-02");
            assertThat(h.thumbnail()).isEqualTo("https://cdn/t.jpg");
            assertThat(h.snippet()).contains(new Segment("house", true));
        });
        assertThat(web.didYouMean()).isEqualTo("houses");
        assertThat(web.hasMore()).isTrue();

        ImagePage images = sx.images("house", 1);
        assertThat(images.images()).hasSize(2);
        assertThat(images.images().getFirst().width()).isEqualTo(1920);
        assertThat(images.images().get(1).thumbnailUrl()).isEqualTo("https://i/2.jpg");
        assertThat(images.images().get(1).width()).isZero();
        assertThat(sx.web("empty", 1).hasMore()).isFalse();
        assertThat(SearxngProvider.resolution("12x")).containsExactly(0, 0);
        assertThat(SearxngProvider.snippet("A house is home ...Read more"))
                .isEqualTo("A house is home");
    }

    @Test
    void openProviderMergesSourcesAndSurvivesOneFailure() {
        stub.on("/wiki/w/api.php?action=query", WIKI).on("/hn/", HN)
                .on("/ov/", OPENVERSE).on("/commons/", COMMONS);
        OpenProvider open = new OpenProvider(new Http(stub.props("open")), stub.props("open"));
        assertThat(open.name()).isEqualTo("open");

        WebPage web = open.web("house", 1);
        assertThat(web.hits()).extracting(WebHit::title)
                .containsExactly("House", "Houseboat", "My house build", "Ask");
        assertThat(web.hits().getFirst().thumbnail()).isEqualTo("https://up.wiki/h.jpg");
        assertThat(web.hits().get(2).age()).isEqualTo("2024-05-01");
        assertThat(web.hits().get(3).snippet()).contains(new Segment("house", true));
        assertThat(web.hasMore()).isTrue();
        assertThat(web.didYouMean()).isNull(); // "house" for "house" is not a correction
        assertThat(open.web("hosue", 1).didYouMean()).isEqualTo("house");

        ImagePage images = open.images("house", 1);
        assertThat(images.images()).extracting(ImageHit::title)
                .containsExactly("house", "Old house");
        assertThat(images.images().get(1).thumbnailUrl()).isEqualTo("https://upload.wiki/o-400.jpg");
        assertThat(images.hasMore()).isTrue();

        stub.fail("/hn/", 503).fail("/commons/", 500);
        assertThat(open.web("house", 1).hits()).hasSize(2);
        assertThat(open.images("house", 1).images()).hasSize(1);
        stub.fail("/wiki/w/api.php?action=query", 500).fail("/ov/", 429);
        assertThatThrownBy(() -> open.web("house", 1)).isInstanceOf(UpstreamException.class);
        assertThatThrownBy(() -> open.images("house", 1)).isInstanceOf(UpstreamException.class);
        assertThat(OpenProvider.realCorrection("house", "")).isNull();
    }

    @Test
    void serviceCachesAnswersAndSuggests() {
        stub.on("/wiki/w/api.php?action=query", WIKI).on("/hn/", HN)
                .on("/ov/", OPENVERSE).on("/commons/", COMMONS)
                .on("/wiki/w/api.php?action=opensearch",
                        json("['hou',['House','Houston','hou','House']]"));
        WebSearchService service = new WebSearchService(stub.props("open"),
                new SimpleMeterRegistry());
        assertThat(service.providerName()).isEqualTo("open");

        WebSearchResponse a = service.search("  House ", "web", 1);
        assertThat(a.query()).isEqualTo("House");
        assertThat(a.source()).isEqualTo("open");
        assertThat(a.results()).isNotEmpty();
        int calls = stub.requests.size();
        assertThat(service.search("house", "web", 1)).isSameAs(a);
        assertThat(stub.requests).hasSize(calls);
        assertThat(service.search("house", "images", 1).images()).hasSize(2);

        assertThat(service.suggest("Hou")).containsExactly("house", "houston");
        assertThat(service.suggest("h")).isEmpty();
        stub.fail("/wiki/w/api.php?action=opensearch", 500);
        assertThat(service.suggest("zzz")).isEmpty();
    }
}
