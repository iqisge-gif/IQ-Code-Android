package com.termux.app.iqcode.tools;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;

final class PathPolicy {
    private PathPolicy() {}

    static File resolve(String projectDirectory, String raw) throws IOException {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("file path is required");
        String path = raw.trim();
        if (path.equals("~")) path = TermuxConstants.TERMUX_HOME_DIR_PATH;
        else if (path.startsWith("~/")) path = TermuxConstants.TERMUX_HOME_DIR_PATH + path.substring(1);
        File file = new File(path);
        if (!file.isAbsolute()) file = new File(projectDirectory, path);
        return file.getCanonicalFile();
    }

    static boolean isDangerousPseudoFile(File file) {
        String p = file.getAbsolutePath();
        return p.equals("/dev/zero") || p.equals("/dev/random") || p.equals("/dev/urandom") ||
            p.equals("/dev/full") || p.equals("/dev/stdin") || p.equals("/dev/stdout") ||
            p.equals("/dev/stderr") || p.equals("/dev/tty") || p.equals("/dev/console") ||
            p.matches("^/proc/[^/]+/fd/[012]$");
    }
}
