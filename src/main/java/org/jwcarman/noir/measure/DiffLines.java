package org.jwcarman.noir.measure;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The lines an attempt added or changed, read from one diff of the base commit against the
 * attempt's branch ({@code Bundles.diffSince}: {@code git diff -U0 base branch -- paths}), so every
 * new-side line number is a line of the attempt's final tree, which is what JaCoCo and PIT report.
 *
 * <p>Hunks are read by their {@code @@} line counts, so content lines that look like headers are
 * never mistaken for diff structure, and {@code \ No newline at end of file} is skipped. A file's
 * path comes from its {@code +++} line (git's C-style quoting, and the trailing tab it adds after a
 * path containing a space, are undone), else its {@code diff --git} header when both sides are
 * equal; a deleted file ({@code +++ /dev/null}) and a binary file (no hunks) have no added lines.
 */
public final class DiffLines {
  private static final Pattern HUNK =
      Pattern.compile("(?s)^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

  private DiffLines() {}

  /**
   * New-side line numbers of added lines, per requested path; a requested path with no added lines
   * maps to an empty set.
   */
  public static Map<String, Set<Integer>> added(String diff, Set<String> paths) {
    var found = new HashMap<String, Set<Integer>>();
    var lines = diff.split("\n", -1);
    int i = 0;
    String path = null;
    while (i < lines.length) {
      var l = lines[i];
      if (l.startsWith("diff --git ")) {
        path = header(l);
        i++;
      } else if (l.startsWith("+++ ")) {
        path = side(l.substring(4), "b/");
        i++;
      } else if (l.startsWith("@@ ")) {
        var m = HUNK.matcher(l);
        i++;
        if (!m.matches()) {
          continue;
        }
        int oldLeft = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        int newLine = Integer.parseInt(m.group(3));
        int newLeft = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
        var added = path == null ? null : found.computeIfAbsent(path, k -> new TreeSet<>());
        while (i < lines.length && (oldLeft > 0 || newLeft > 0)) {
          var h = lines[i++];
          if (h.startsWith("\\")) {
            continue; // "\ No newline at end of file"
          }
          char c = h.isEmpty() ? ' ' : h.charAt(0);
          if (c == '+') {
            if (added != null) {
              added.add(newLine);
            }
            newLine++;
            newLeft--;
          } else if (c == '-') {
            oldLeft--;
          } else {
            newLine++;
            newLeft--;
            oldLeft--;
          }
        }
      } else {
        i++;
      }
    }
    var result = new LinkedHashMap<String, Set<Integer>>();
    for (var p : paths) {
      result.put(p, Set.copyOf(found.getOrDefault(p, Set.of())));
    }
    return result;
  }

  /** A {@code ---}/{@code +++} path without its side prefix, or null for {@code /dev/null}. */
  private static String side(String raw, String prefix) {
    var p = raw;
    if (p.endsWith("\t")) {
      p = p.substring(0, p.length() - 1);
    }
    p = unquote(p);
    if (p.equals("/dev/null")) {
      return null;
    }
    return p.startsWith(prefix) ? p.substring(prefix.length()) : p;
  }

  /** The path of an unquoted {@code diff --git a/P b/P} header with equal sides, or null. */
  private static String header(String line) {
    var rest = line.substring("diff --git ".length());
    if (rest.startsWith("\"")) {
      int close = closingQuote(rest);
      if (close < 0) {
        return null;
      }
      var a = unquote(rest.substring(0, close + 1));
      return a.startsWith("a/") ? a.substring(2) : null;
    }
    if ((rest.length() - 1) % 2 != 0 || rest.length() < 5) {
      return null;
    }
    int half = (rest.length() - 1) / 2;
    var a = rest.substring(0, half);
    var b = rest.substring(half + 1);
    if (a.startsWith("a/") && b.startsWith("b/") && a.substring(2).equals(b.substring(2))) {
      return a.substring(2);
    }
    return null;
  }

  private static int closingQuote(String s) {
    for (int k = 1; k < s.length(); k++) {
      if (s.charAt(k) == '\\') {
        k++;
      } else if (s.charAt(k) == '"') {
        return k;
      }
    }
    return -1;
  }

  /** Undoes git's C-style path quoting ({@code "a/\303\251"}); unquoted text is returned as is. */
  static String unquote(String s) {
    if (s.length() < 2 || !s.startsWith("\"") || !s.endsWith("\"")) {
      return s;
    }
    var bytes = new ByteArrayOutputStream();
    var body = s.substring(1, s.length() - 1);
    for (int k = 0; k < body.length(); k++) {
      char c = body.charAt(k);
      if (c != '\\' || k + 1 >= body.length()) {
        bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
        continue;
      }
      char e = body.charAt(++k);
      if (e >= '0' && e <= '7' && k + 2 < body.length()) {
        bytes.write(Integer.parseInt(body.substring(k, k + 3), 8));
        k += 2;
        continue;
      }
      char decoded =
          switch (e) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'a' -> (char) 7;
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'r' -> '\r';
            case 'v' -> (char) 11;
            default -> e;
          };
      bytes.writeBytes(String.valueOf(decoded).getBytes(StandardCharsets.UTF_8));
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }
}
