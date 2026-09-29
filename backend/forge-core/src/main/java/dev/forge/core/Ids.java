package dev.forge.core;












public final class Ids {


    private Ids() {
    }
























    public static String random(String prefix) {
        return IdGenerator.random(prefix);
    }
}
