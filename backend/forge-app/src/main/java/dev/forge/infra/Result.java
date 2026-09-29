package dev.forge.infra;

import dev.forge.core.ForgeException;
import dev.forge.core.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

record Result(int exitCode, String output) {
    private static final Log log = Log.of(Result.class);

    boolean ok() {
        return exitCode == 0;
    }

    String orThrow(String what) {
        if (!ok()) {
            log.debug("External operation failed: " + what);
            throw ForgeException.conflict(what + " failed; check repository state and server logs");
        }
        return output;
    }


}
