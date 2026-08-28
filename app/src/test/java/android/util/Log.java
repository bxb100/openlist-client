package android.util;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Minimal Android Log implementation for dependencies exercised by local JVM tests. */
public final class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    private Log() {}

    public static boolean isLoggable(String tag, int priority) {
        return false;
    }

    public static int println(int priority, String tag, String message) {
        return 0;
    }

    public static String getStackTraceString(Throwable throwable) {
        if (throwable == null) return "";
        StringWriter output = new StringWriter();
        throwable.printStackTrace(new PrintWriter(output));
        return output.toString();
    }

    public static int v(String tag, String message) { return 0; }
    public static int v(String tag, String message, Throwable throwable) { return 0; }
    public static int d(String tag, String message) { return 0; }
    public static int d(String tag, String message, Throwable throwable) { return 0; }
    public static int i(String tag, String message) { return 0; }
    public static int i(String tag, String message, Throwable throwable) { return 0; }
    public static int w(String tag, String message) { return 0; }
    public static int w(String tag, String message, Throwable throwable) { return 0; }
    public static int w(String tag, Throwable throwable) { return 0; }
    public static int e(String tag, String message) { return 0; }
    public static int e(String tag, String message, Throwable throwable) { return 0; }
    public static int wtf(String tag, String message) { return 0; }
    public static int wtf(String tag, String message, Throwable throwable) { return 0; }
    public static int wtf(String tag, Throwable throwable) { return 0; }
}
