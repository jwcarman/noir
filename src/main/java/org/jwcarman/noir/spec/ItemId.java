package org.jwcarman.noir.spec;

import java.time.Clock;
import java.util.Locale;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/** Work-item identifiers: {@code nr-} followed by a 26-character Crockford base32 ULID. */
public final class ItemId {
  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final Pattern VALID = Pattern.compile("nr-[0-7][0-9A-HJKMNP-TV-Z]{25}");

  private ItemId() {}

  public static String newId(Clock clock, RandomGenerator rnd) {
    char[] chars = new char[26];
    long ts = clock.millis();
    for (int i = 9; i >= 0; i--) {
      chars[i] = ALPHABET[(int) (ts & 31)];
      ts >>>= 5;
    }
    for (int i = 10; i < 26; i++) {
      chars[i] = ALPHABET[rnd.nextInt(32)];
    }
    return "nr-" + new String(chars);
  }

  public static boolean isValid(String id) {
    return id != null && VALID.matcher(id).matches();
  }

  /** The Java package holdout tests for this item must live in. */
  public static String holdoutPackage(String id) {
    return "noir.holdout." + id.toLowerCase(Locale.ROOT).replace('-', '_');
  }
}
