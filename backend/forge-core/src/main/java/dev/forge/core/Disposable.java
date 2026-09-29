package dev.forge.core;

import java.util.ArrayDeque;
import java.util.Deque;

@FunctionalInterface
public interface Disposable {
    void dispose();
}
