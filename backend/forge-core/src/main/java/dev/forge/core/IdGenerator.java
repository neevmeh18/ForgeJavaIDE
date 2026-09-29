package dev.forge.core;

import java.security.SecureRandom;
import java.util.Base64;

final class IdGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private IdGenerator() {}
    static String random(String prefix) {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return prefix + "-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
