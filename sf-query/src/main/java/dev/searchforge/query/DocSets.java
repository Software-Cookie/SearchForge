package dev.searchforge.query;

import dev.searchforge.indexer.IntList;

// Set operations on sorted, duplicate-free docId arrays.
public final class DocSets {

    private DocSets() {
    }

    // Galloping (exponential) intersection: for each element of the shorter list, gallop
    // forward in the longer one. O(m log(n/m)) when sizes are very different, which is
    // the common case ("java" AND a rare word).
    public static int[] intersect(int[] a, int[] b) {
        if (a.length > b.length) {
            int[] t = a;
            a = b;
            b = t;
        }
        IntList out = new IntList(Math.max(1, a.length));
        int lo = 0;
        for (int x : a) {
            lo = gallop(b, lo, x);
            if (lo >= b.length) {
                break;
            }
            if (b[lo] == x) {
                out.add(x);
                lo++;
            }
        }
        return out.toArray();
    }

    // First index i >= from with arr[i] >= target (arr.length if none).
    static int gallop(int[] arr, int from, int target) {
        if (from >= arr.length || arr[from] >= target) {
            return from;
        }
        int step = 1;
        int lo = from;
        int hi = from + 1;
        while (hi < arr.length && arr[hi] < target) {
            lo = hi;
            step <<= 1;
            hi = from + step;
        }
        hi = Math.min(hi, arr.length);
        // invariant: arr[lo] < target, and (hi == length or arr[hi] >= target)
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid] < target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return hi;
    }

    public static int[] union(int[] a, int[] b) {
        IntList out = new IntList(a.length + b.length);
        int i = 0;
        int j = 0;
        while (i < a.length || j < b.length) {
            if (j >= b.length || (i < a.length && a[i] < b[j])) {
                out.add(a[i++]);
            } else if (i >= a.length || b[j] < a[i]) {
                out.add(b[j++]);
            } else {
                out.add(a[i]);
                i++;
                j++;
            }
        }
        return out.toArray();
    }

    public static int[] subtract(int[] a, int[] b) {
        IntList out = new IntList(a.length);
        int j = 0;
        for (int x : a) {
            j = gallop(b, j, x);
            if (j >= b.length || b[j] != x) {
                out.add(x);
            }
        }
        return out.toArray();
    }
}
