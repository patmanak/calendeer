// SPDX-License-Identifier: GPL-3.0-or-later
package android.os;

/** JVM-only monotonic clock for Core's request logger. Never packaged into app/test APKs.
 * This supplies no Android lifecycle, suspend, sleep or device clock qualification.
 */
public final class SystemClock {
    private SystemClock() {}
    public static long elapsedRealtime() { return System.nanoTime() / 1_000_000L; }
}
