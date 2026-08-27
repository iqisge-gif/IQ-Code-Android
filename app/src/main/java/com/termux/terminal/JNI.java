package com.termux.terminal;

/** Native PTY bridge used by TerminalSession. The host app explicitly loads libtermux.so first. */
public final class JNI {
    private static volatile boolean loaded;
    private static volatile Throwable loadError;

    private JNI() { }

    /**
     * Explicit absolute-path loading avoids ClassLoader/nativeLibraryDir edge cases in manually packaged APKs.
     * We deliberately do not load in a static initializer: a failed static initializer poisons the class and
     * every later attempt turns into a misleading NoClassDefFoundError.
     */
    public static synchronized void load(String absolutePath) {
        if (loaded) return;
        try {
            if (absolutePath != null && !absolutePath.trim().isEmpty()) System.load(absolutePath);
            else System.loadLibrary("termux");
            loaded = true;
            loadError = null;
        } catch (Throwable t) {
            loadError = t;
            throw t;
        }
    }

    public static boolean isLoaded() { return loaded; }
    public static Throwable getLoadError() { return loadError; }

    public static native int createSubprocess(String cmd, String cwd, String[] args, String[] envVars,
                                               int[] processId, int rows, int columns, int cellWidth, int cellHeight);
    public static native void setPtyWindowSize(int fd, int rows, int cols, int cellWidth, int cellHeight);
    public static native int waitFor(int processId);
    public static native void close(int fileDescriptor);
}
