package org.jwcarman.noir.factory;

import java.text.Normalizer;
import java.util.Locale;

/** Branch names for work items: {@code noir/<issue>-<slug>}. */
public final class BranchNames {
  static final int MAX_SLUG = 40;

  private BranchNames() {}

  /**
   * {@code slug} is the title lowercased, with accents removed and every run of other characters
   * that are not ASCII letters or digits replaced by one {@code -}, trimmed, at most 40 characters.
   * A title with no usable characters gives {@code noir/<issue>}.
   */
  public static String of(int issue, String title) {
    var ascii =
        Normalizer.normalize(title, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT);
    var slug = trim(ascii.replaceAll("[^a-z0-9]+", "-"));
    if (slug.length() > MAX_SLUG) {
      slug = trim(slug.substring(0, MAX_SLUG));
    }
    return slug.isEmpty() ? "noir/" + issue : "noir/" + issue + "-" + slug;
  }

  private static String trim(String s) {
    return s.replaceAll("^-+|-+$", "");
  }
}
