package dev.searchforge.crawler.robots;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// RFC 9309 robots.txt parser. Picks the group(s) naming our product token, else "*".
public final class RobotsTxtParser {

    private static final int MAX_PATTERN = 2048;

    private record Group(List<String> agents, List<RobotsRules.Rule> rules, long[] delay) {
    }

    private RobotsTxtParser() {
    }

    public static RobotsRules parse(String text, String agentToken) {
        List<Group> groups = parseGroups(text == null ? "" : text);
        String token = agentToken.toLowerCase(Locale.ROOT);
        List<Group> chosen = new ArrayList<>();
        for (Group g : groups) {
            for (String a : g.agents()) {
                if (!a.isEmpty() && !a.equals("*") && token.contains(a)) {
                    chosen.add(g);
                    break;
                }
            }
        }
        if (chosen.isEmpty()) {
            for (Group g : groups) {
                if (g.agents().contains("*")) {
                    chosen.add(g);
                }
            }
        }
        List<RobotsRules.Rule> rules = new ArrayList<>();
        long delay = -1;
        for (Group g : chosen) {
            rules.addAll(g.rules());
            delay = Math.max(delay, g.delay()[0]);
        }
        return new RobotsRules(rules, delay);
    }

    private static List<Group> parseGroups(String text) {
        List<Group> groups = new ArrayList<>();
        Group current = null;
        boolean lastWasAgent = false;
        for (String rawLine : text.split("\\r?\\n|\\r")) {
            String line = stripComment(rawLine).strip();
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (key.equals("user-agent")) {
                if (current == null || !lastWasAgent) {
                    current = new Group(new ArrayList<>(), new ArrayList<>(), new long[] {-1});
                    groups.add(current);
                }
                current.agents().add(agentName(value));
                lastWasAgent = true;
                continue;
            }
            lastWasAgent = false;
            if (current == null) {
                continue; // rules before any user-agent line are ignored
            }
            applyRule(current, key, value);
        }
        return groups;
    }

    private static void applyRule(Group g, String key, String value) {
        switch (key) {
            case "allow", "disallow" -> {
                if (value.length() <= MAX_PATTERN) {
                    g.rules().add(new RobotsRules.Rule(key.equals("allow"), value));
                }
            }
            case "crawl-delay" -> {
                try {
                    double seconds = Double.parseDouble(value);
                    if (seconds >= 0 && seconds <= 86_400) {
                        g.delay()[0] = Math.max(g.delay()[0], (long) (seconds * 1000));
                    }
                } catch (NumberFormatException ignored) {
                    // malformed crawl-delay lines are skipped, as browsers and Google do
                }
            }
            default -> {
                // sitemap and unknown keys are not used
            }
        }
    }

    private static String agentName(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        int slash = v.indexOf('/');
        if (slash > 0) {
            v = v.substring(0, slash);
        }
        int space = v.indexOf(' ');
        return space > 0 ? v.substring(0, space) : v;
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(0, hash) : line;
    }
}
