package com.termux.shared.termux;

import java.io.File;

/** Minimal runtime constants for the com.iqge coexist build. */
public final class TermuxConstants {
    public static final String TERMUX_PACKAGE_NAME = "com.iqge";
    public static final String TERMUX_FILES_DIR_PATH = "/data/user/0/com.iqge/files";
    public static final String TERMUX_HOME_DIR_PATH = TERMUX_FILES_DIR_PATH + "/home";
    public static final String TERMUX_PREFIX_DIR_PATH = TERMUX_FILES_DIR_PATH + "/usr";
    public static final String TERMUX_BIN_PREFIX_DIR_PATH = TERMUX_PREFIX_DIR_PATH + "/bin";
    public static final File TERMUX_HOME_DIR = new File(TERMUX_HOME_DIR_PATH);
    public static final File TERMUX_PREFIX_DIR = new File(TERMUX_PREFIX_DIR_PATH);
    private TermuxConstants() {}
}
