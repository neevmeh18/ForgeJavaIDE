package dev.forge.auth;

import java.util.Map;
import java.util.Optional;

public record Credentials(String username, char[] secret, Map<String, String> extra) {
    public Credentials {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }


    public void wipe() {
        if (secret != null) {
            java.util.Arrays.fill(secret, '\0');
        }
    }
}
