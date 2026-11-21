package android.text.format;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Custom reimplementation of the android.text.format.DateFormat static format(...)
 * helpers extensions use (e.g. manhuaren's request-signature timestamp). The
 * android.text.format.DateFormat pattern syntax follows java.text.SimpleDateFormat,
 * so we delegate to it. Locale.US keeps numeric signing patterns such as
 * "yyyy-MM-dd+HH:mm:ss" Gregorian and stable regardless of device locale.
 */
public class DateFormat {
    private DateFormat() {
        throw new RuntimeException("Stub!");
    }

    public static CharSequence format(CharSequence inFormat, long inTimeInMillis) {
        return format(inFormat, new Date(inTimeInMillis));
    }

    public static CharSequence format(CharSequence inFormat, Date inDate) {
        return new SimpleDateFormat(inFormat.toString(), Locale.US).format(inDate);
    }

    public static CharSequence format(CharSequence inFormat, Calendar inCalendar) {
        return format(inFormat, inCalendar.getTime());
    }
}
