package android.text.format;

import android.content.Context;

import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Custom reimplementation of {@code android.text.format.DateFormat} for the
 * embedded Suwayomi runtime.
 *
 * <p>The Android SDK stub jar bundled in the server fat jar ships every method
 * of this class as {@code throw new RuntimeException("Stub!")}. Any installed
 * extension that calls {@code DateFormat.format(...)} to build a request — e.g.
 * Manhuaren (漫画人) signs every API call with a {@code gts} timestamp produced
 * by {@code DateFormat.format("yyyy-MM-dd+HH:mm:ss", new Date())} — therefore
 * throws and returns HTTP 500 BEFORE the request is ever sent. Popular, latest
 * and search all die; only {@code getFilterList} (which issues no request)
 * survives. Observed on-device as:
 *
 * <pre>
 *   java.lang.RuntimeException: Stub!
 *       at android.text.format.DateFormat.format(DateFormat.java:41)
 *       at ...Manhuaren.popularMangaRequest(Unknown Source)
 * </pre>
 *
 * <p>This class shadows the stub with a real, dependency-free implementation. It
 * deliberately does NOT use {@code android.text.SpannableStringBuilder} (itself
 * a stub in the same SDK jar); it is a plain {@link Calendar} + {@link java.text}
 * port of AOSP's char-by-char pattern walker. Adding a real class under
 * {@code src/main/java/android/text/format/} mirrors the existing
 * {@code Formatter.java} override and shadows the stub on the runtime classpath.
 *
 * <p>This is a generic runtime fix: every extension that formats dates through
 * this API benefits, with no per-source code.
 */
public class DateFormat {

    public static final char QUOTE = '\'';

    public DateFormat() {
        // The real Android class is instantiable; keep a public ctor so any
        // reference compiled against the stub still links.
    }

    public static CharSequence format(CharSequence inFormat, long inTimeInMillis) {
        Calendar c = new GregorianCalendar();
        c.setTimeInMillis(inTimeInMillis);
        return format(inFormat, c);
    }

    public static CharSequence format(CharSequence inFormat, Date inDate) {
        Calendar c = new GregorianCalendar();
        c.setTime(inDate);
        return format(inFormat, c);
    }

    public static CharSequence format(CharSequence inFormat, Calendar inDate) {
        final int len = inFormat.length();
        final StringBuilder out = new StringBuilder(len + 16);
        final DateFormatSymbols symbols = DateFormatSymbols.getInstance(Locale.getDefault());

        int i = 0;
        while (i < len) {
            final char c = inFormat.charAt(i);

            if (c == QUOTE) {
                i = appendQuotedText(inFormat, i, len, out);
                continue;
            }

            int count = 1;
            while ((i + count < len) && (inFormat.charAt(i + count) == c)) {
                count++;
            }

            final String replacement = fieldValue(c, count, inDate, symbols);
            if (replacement != null) {
                out.append(replacement);
            } else {
                // Unrecognised character (separators like '-', '+', ':' and any
                // pattern letter we do not handle) passes through literally.
                for (int k = 0; k < count; k++) {
                    out.append(c);
                }
            }
            i += count;
        }
        return out.toString();
    }

    private static String fieldValue(char c, int count, Calendar inDate, DateFormatSymbols symbols) {
        switch (c) {
            case 'A':
            case 'a':
                return symbols.getAmPmStrings()[inDate.get(Calendar.AM_PM)];
            case 'd':
                return zeroPad(inDate.get(Calendar.DATE), count);
            case 'c':
            case 'E': {
                int dow = inDate.get(Calendar.DAY_OF_WEEK);
                return (count <= 3) ? symbols.getShortWeekdays()[dow] : symbols.getWeekdays()[dow];
            }
            case 'K': // hour in am/pm (0-11)
                return zeroPad(inDate.get(Calendar.HOUR), count);
            case 'h': { // hour in am/pm (1-12)
                int h = inDate.get(Calendar.HOUR);
                if (h == 0) h = 12;
                return zeroPad(h, count);
            }
            case 'H': // hour in day (0-23)
                return zeroPad(inDate.get(Calendar.HOUR_OF_DAY), count);
            case 'k': { // hour in day (1-24)
                int h = inDate.get(Calendar.HOUR_OF_DAY);
                if (h == 0) h = 24;
                return zeroPad(h, count);
            }
            case 'L':
            case 'M': {
                int month = inDate.get(Calendar.MONTH);
                if (count >= 4) return symbols.getMonths()[month];
                if (count == 3) return symbols.getShortMonths()[month];
                return zeroPad(month + 1, count);
            }
            case 'm':
                return zeroPad(inDate.get(Calendar.MINUTE), count);
            case 's':
                return zeroPad(inDate.get(Calendar.SECOND), count);
            case 'y': {
                int year = inDate.get(Calendar.YEAR);
                return (count <= 2) ? zeroPad(year % 100, 2) : zeroPad(year, count);
            }
            case 'z': {
                TimeZone tz = inDate.getTimeZone();
                boolean dst = inDate.get(Calendar.DST_OFFSET) != 0;
                return tz.getDisplayName(dst, (count < 4) ? TimeZone.SHORT : TimeZone.LONG);
            }
            default:
                return null;
        }
    }

    private static String zeroPad(int value, int width) {
        String s = Integer.toString(value);
        while (s.length() < width) {
            s = "0" + s;
        }
        return s;
    }

    /**
     * Appends the quoted run that starts at {@code start} (which points at a
     * {@link #QUOTE}). {@code ''} emits a single literal quote. Returns the index
     * just past the closing quote.
     */
    private static int appendQuotedText(CharSequence s, int start, int len, StringBuilder out) {
        if (start + 1 < len && s.charAt(start + 1) == QUOTE) {
            out.append(QUOTE);
            return start + 2;
        }
        int i = start + 1;
        while (i < len) {
            char c = s.charAt(i);
            if (c == QUOTE) {
                if (i + 1 < len && s.charAt(i + 1) == QUOTE) {
                    out.append(QUOTE);
                    i += 2;
                    continue;
                }
                i += 1; // consume the closing quote
                break;
            }
            out.append(c);
            i += 1;
        }
        return i;
    }

    // --- Common static helpers other extensions may reference. Real java-backed
    // --- implementations (an improvement over the original Stub! throwers).

    public static boolean is24HourFormat(Context context) {
        return true;
    }

    public static char[] getDateFormatOrder(Context context) {
        return new char[] { 'y', 'M', 'd' };
    }

    public static java.text.DateFormat getDateFormat(Context context) {
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT);
    }

    public static java.text.DateFormat getTimeFormat(Context context) {
        return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT);
    }

    public static java.text.DateFormat getLongDateFormat(Context context) {
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG);
    }

    public static java.text.DateFormat getMediumDateFormat(Context context) {
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
    }
}
