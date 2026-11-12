package dev.searchforge.indexer;

import java.util.Arrays;

// Growable int array without boxing (an Integer list costs ~4x the memory).
public final class IntList {

    private int[] data;
    private int size;

    public IntList() {
        this(8);
    }

    public IntList(int capacity) {
        data = new int[Math.max(1, capacity)];
    }

    public void add(int v) {
        if (size == data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        data[size++] = v;
    }

    public void addAll(IntList other) {
        for (int i = 0; i < other.size; i++) {
            add(other.data[i]);
        }
    }

    public int get(int i) {
        if (i >= size) {
            throw new IndexOutOfBoundsException(i);
        }
        return data[i];
    }

    public int size() {
        return size;
    }

    public int[] toArray() {
        return Arrays.copyOf(data, size);
    }
}
