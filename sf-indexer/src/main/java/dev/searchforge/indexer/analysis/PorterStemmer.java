package dev.searchforge.indexer.analysis;

// Martin Porter's 1980 suffix-stripping algorithm ("An algorithm for suffix stripping",
// Program 14(3)), steps 1a-5b, following the structure of Porter's reference version.
// Not thread-safe per instance; use the static stem() which allocates per call.
public final class PorterStemmer {

    private final char[] b;
    private int k;
    private int j;

    private PorterStemmer(String word) {
        this.b = word.toCharArray();
        this.k = b.length - 1;
    }

    public static String stem(String word) {
        if (word.length() <= 2 || !isAsciiLower(word)) {
            return word;
        }
        PorterStemmer s = new PorterStemmer(word);
        s.step1ab();
        if (s.k > 0) {
            s.step1c();
            s.step2();
            s.step3();
            s.step4();
            s.step5();
        }
        return new String(s.b, 0, s.k + 1);
    }

    private static boolean isAsciiLower(String w) {
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }

    private boolean cons(int i) {
        switch (b[i]) {
            case 'a', 'e', 'i', 'o', 'u':
                return false;
            case 'y':
                return i == 0 || !cons(i - 1);
            default:
                return true;
        }
    }

    // m() = number of VC sequences in b[0..j].
    private int m() {
        int n = 0;
        int i = 0;
        while (true) {
            if (i > j) {
                return n;
            }
            if (!cons(i)) {
                break;
            }
            i++;
        }
        i++;
        while (true) {
            while (true) {
                if (i > j) {
                    return n;
                }
                if (cons(i)) {
                    break;
                }
                i++;
            }
            i++;
            n++;
            while (true) {
                if (i > j) {
                    return n;
                }
                if (!cons(i)) {
                    break;
                }
                i++;
            }
            i++;
        }
    }

    private boolean vowelInStem() {
        for (int i = 0; i <= j; i++) {
            if (!cons(i)) {
                return true;
            }
        }
        return false;
    }

    private boolean doubleC(int i) {
        return i >= 1 && b[i] == b[i - 1] && cons(i);
    }

    // cvc(i): b[i-2..i] is consonant-vowel-consonant and the last is not w, x or y.
    private boolean cvc(int i) {
        if (i < 2 || !cons(i) || cons(i - 1) || !cons(i - 2)) {
            return false;
        }
        char ch = b[i];
        return ch != 'w' && ch != 'x' && ch != 'y';
    }

    private boolean ends(String s) {
        int len = s.length();
        int o = k - len + 1;
        if (o < 0) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (b[o + i] != s.charAt(i)) {
                return false;
            }
        }
        j = k - len;
        return true;
    }

    private void setTo(String s) {
        int len = s.length();
        int o = j + 1;
        for (int i = 0; i < len; i++) {
            b[o + i] = s.charAt(i);
        }
        k = j + len;
    }

    private void r(String s) {
        if (m() > 0) {
            setTo(s);
        }
    }

    private void step1ab() {
        if (b[k] == 's') {
            if (ends("sses")) {
                k -= 2;
            } else if (ends("ies")) {
                setTo("i");
            } else if (b[k - 1] != 's') {
                k--;
            }
        }
        if (ends("eed")) {
            if (m() > 0) {
                k--;
            }
        } else if ((ends("ed") || ends("ing")) && vowelInStem()) {
            k = j;
            if (ends("at")) {
                setTo("ate");
            } else if (ends("bl")) {
                setTo("ble");
            } else if (ends("iz")) {
                setTo("ize");
            } else if (doubleC(k)) {
                k--;
                char ch = b[k];
                if (ch == 'l' || ch == 's' || ch == 'z') {
                    k++;
                }
            } else if (m() == 1 && cvc(k)) {
                setTo("e");
            }
        }
    }

    private void step1c() {
        if (ends("y") && vowelInStem()) {
            b[k] = 'i';
        }
    }

    private boolean replace(String[][] pairs) {
        for (String[] p : pairs) {
            if (ends(p[0])) {
                r(p[1]);
                return true;
            }
        }
        return false;
    }

    private void step2() {
        String[][] pairs = switch (b[k - 1]) {
            case 'a' -> new String[][] {{"ational", "ate"}, {"tional", "tion"}};
            case 'c' -> new String[][] {{"enci", "ence"}, {"anci", "ance"}};
            case 'e' -> new String[][] {{"izer", "ize"}};
            case 'l' -> new String[][] {{"bli", "ble"}, {"alli", "al"}, {"entli", "ent"},
                {"eli", "e"}, {"ousli", "ous"}};
            case 'o' -> new String[][] {{"ization", "ize"}, {"ation", "ate"}, {"ator", "ate"}};
            case 's' -> new String[][] {{"alism", "al"}, {"iveness", "ive"},
                {"fulness", "ful"}, {"ousness", "ous"}};
            case 't' -> new String[][] {{"aliti", "al"}, {"iviti", "ive"}, {"biliti", "ble"}};
            case 'g' -> new String[][] {{"logi", "log"}};
            default -> new String[0][];
        };
        replace(pairs);
    }

    private void step3() {
        String[][] pairs = switch (b[k]) {
            case 'e' -> new String[][] {{"icate", "ic"}, {"ative", ""}, {"alize", "al"}};
            case 'i' -> new String[][] {{"iciti", "ic"}};
            case 'l' -> new String[][] {{"ical", "ic"}, {"ful", ""}};
            case 's' -> new String[][] {{"ness", ""}};
            default -> new String[0][];
        };
        replace(pairs);
    }

    private void step4() {
        if (k < 1) {
            return;
        }
        boolean found = switch (b[k - 1]) {
            case 'a' -> ends("al");
            case 'c' -> ends("ance") || ends("ence");
            case 'e' -> ends("er");
            case 'i' -> ends("ic");
            case 'l' -> ends("able") || ends("ible");
            case 'n' -> ends("ant") || ends("ement") || ends("ment") || ends("ent");
            case 'o' -> (ends("ion") && j >= 0 && (b[j] == 's' || b[j] == 't')) || ends("ou");
            case 's' -> ends("ism");
            case 't' -> ends("ate") || ends("iti");
            case 'u' -> ends("ous");
            case 'v' -> ends("ive");
            case 'z' -> ends("ize");
            default -> false;
        };
        if (found && m() > 1) {
            k = j;
        }
    }

    private void step5() {
        j = k;
        if (b[k] == 'e') {
            int a = m();
            if (a > 1 || (a == 1 && !cvc(k - 1))) {
                k--;
            }
        }
        if (b[k] == 'l' && doubleC(k) && m() > 1) {
            k--;
        }
    }
}
