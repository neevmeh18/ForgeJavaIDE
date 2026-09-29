package dev.forge.core;

import java.util.regex.Pattern;

final class IdValidator {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:@-]{1,128}");
    private IdValidator() {}
    static String check(String value, String what) {
        if (value == null || !VALID.matcher(value).matches()) {
            throw ForgeException.invalidArgument("Invalid " + what + ": " + value);
        }
        return value;
    }
}
