package android.net;

import android.os.Parcel;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Minimal non-null Uri for local JVM tests where android.net.Uri.parse is not implemented. */
public final class TestUri extends Uri {
    public static final TestUri INSTANCE = new TestUri();
    private final String value;

    private TestUri() {
        this("https://example.test/object");
    }

    private TestUri(String value) {
        this.value = Objects.requireNonNull(value);
    }

    /** Creates a Uri whose string form can be consumed by pure-JVM HTTP clients. */
    public static TestUri from(String value) {
        return new TestUri(value);
    }

    @Override public Builder buildUpon() { return null; }
    @Override public String getAuthority() { return null; }
    @Override public String getEncodedAuthority() { return null; }
    @Override public String getEncodedFragment() { return null; }
    @Override public String getEncodedPath() { return "/object"; }
    @Override public String getEncodedQuery() { return null; }
    @Override public String getEncodedSchemeSpecificPart() { return "//example.test/object"; }
    @Override public String getEncodedUserInfo() { return null; }
    @Override public String getFragment() { return null; }
    @Override public String getHost() { return "example.test"; }
    @Override public String getLastPathSegment() { return "object"; }
    @Override public String getPath() { return "/object"; }
    @Override public List<String> getPathSegments() { return Collections.singletonList("object"); }
    @Override public int getPort() { return -1; }
    @Override public String getQuery() { return null; }
    @Override public String getScheme() { return "https"; }
    @Override public String getSchemeSpecificPart() { return "//example.test/object"; }
    @Override public String getUserInfo() { return null; }
    @Override public boolean isHierarchical() { return true; }
    @Override public boolean isRelative() { return false; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel destination, int flags) {}
    @Override public String toString() { return value; }
}
