package dev.forge.core;

import java.util.ArrayDeque;
import java.util.Deque;

public interface Component extends Disposable {
    void start();

    @Override
    default void dispose() {
    }
}
