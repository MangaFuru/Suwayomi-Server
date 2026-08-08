package app.cash.quickjs;

import java.io.Closeable;

/**
 * Graal-free QuickJs stub for the GoManga lite runtime (GraalJS stack stripped
 * by 35-slim-suwayomi-jar.sh; it was already inoperative after the shaded-ICU
 * drop). create() succeeds; evaluate/execute/set throw QuickJsException —
 * "JavaScript engine is not available in this runtime".
 */
public final class QuickJs implements Closeable {

    private static final String UNAVAILABLE =
            "JavaScript engine is not available in this runtime";

    public static QuickJs create() {
        return new QuickJs();
    }

    public QuickJs() {
    }

    public Object evaluate(String script, String ignoredFileName) {
        return this.evaluate(script);
    }

    public Object evaluate(String script) {
        throw new QuickJsException(UNAVAILABLE, null);
    }

    public byte[] compile(String sourceCode, String ignoredFileName) {
        return sourceCode.getBytes();
    }

    public Object execute(byte[] bytecode) {
        throw new QuickJsException(UNAVAILABLE, null);
    }

    public <T> void set(String name, Class<T> ignoredType, T object) {
        throw new QuickJsException(UNAVAILABLE, null);
    }

    @Override
    public void close() {
    }
}
