package dev.searchforge.crawler.robots;

import java.util.List;

// The rules of one robots.txt group (merged) for our agent.
// RFC 9309 2.2.2: the longest matching pattern wins; on a tie, Allow wins.
public record RobotsRules(List<Rule> rules, long crawlDelayMs) {

    public record Rule(boolean allow, String pattern) {
    }

    public static final RobotsRules ALLOW_ALL = new RobotsRules(List.of(), -1);
    public static final RobotsRules DISALLOW_ALL =
            new RobotsRules(List.of(new Rule(false, "/")), -1);

    public RobotsRules {
        rules = List.copyOf(rules);
    }

    // pathAndQuery is the URL path plus "?query" if any, e.g. "/a/b?x=1".
    public boolean isAllowed(String pathAndQuery) {
        String path = pathAndQuery == null || pathAndQuery.isEmpty() ? "/" : pathAndQuery;
        if (path.equals("/robots.txt")) {
            return true;
        }
        int bestLen = -1;
        boolean bestAllow = true;
        for (Rule r : rules) {
            if (r.pattern().isEmpty() || !matches(r.pattern(), path)) {
                continue;
            }
            int len = r.pattern().length();
            if (len > bestLen || (len == bestLen && r.allow())) {
                bestLen = len;
                bestAllow = r.allow();
            }
        }
        return bestAllow;
    }

    // Glob match where '*' is any sequence and a trailing '$' anchors the end.
    // Iterative with single backtrack point: O(n*m) worst case, no regex (no ReDoS).
    static boolean matches(String pattern, String path) {
        boolean anchored = pattern.endsWith("$");
        String p = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
        int pi = 0;
        int si = 0;
        int star = -1;
        int mark = 0;
        while (si < path.length()) {
            if (pi < p.length() && p.charAt(pi) == '*') {
                star = pi++;
                mark = si;
            } else if (pi < p.length() && p.charAt(pi) == path.charAt(si)) {
                pi++;
                si++;
            } else if (pi == p.length() && !anchored) {
                return true; // pattern is a prefix of the path
            } else if (star >= 0) {
                pi = star + 1;
                si = ++mark;
            } else {
                return false;
            }
        }
        while (pi < p.length() && p.charAt(pi) == '*') {
            pi++;
        }
        return pi == p.length();
    }
}
