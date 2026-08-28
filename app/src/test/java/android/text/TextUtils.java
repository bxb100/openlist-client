package android.text;

/** Minimal Android TextUtils implementation used by Media3 HTTP helpers in local JVM tests. */
public final class TextUtils {
    private TextUtils() {}

    public static boolean isEmpty(CharSequence value) {
        return value == null || value.length() == 0;
    }
}
