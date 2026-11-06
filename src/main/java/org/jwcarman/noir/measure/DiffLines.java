package org.jwcarman.noir.measure;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The lines an attempt added or changed, read from its patch.
 *
 * <p>The patch is {@code git log -p} output (see {@code BundleRepo.patchSince}): one diff per
 * commit, each numbered against the tree before that commit. The commits are replayed in order, so
 * a line added by an early commit is shifted by later insertions and deletions, and dropped if a
 * later commit deletes it; the result is numbered against the attempt's final tree.
 *
 * <p>Hunks are read by their line counts, so commit messages and content lines that look like
 * headers are never mistaken for diff structure. Paths come from {@code rename from/to} and the
 * {@code ---}/{@code +++} lines (git's C-style quoting and the trailing tab it adds after a path
 * containing a space are undone), falling back to the {@code diff --git} header for diffs with
 * neither (binary or mode-only changes, which carry no line numbers). Merge commits are not
 * expected in an attempt; with {@code -m} each parent's diff would be replayed in turn.
 */
public final class DiffLines {
  private static final Pattern HUNK =
      Pattern.compile("(?s)^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

  private DiffLines() {}

  /**
   * New-side line numbers of added lines, per requested path; a requested path with no added lines
   * maps to an empty set.
   */
  public static Map<String, Set<Integer>> added(String patch, Set<String> paths) {
    var state = new HashMap<String, TreeSet<Integer>>();
    var lines = patch.split("\n", -1);
    int i = 0;
    while (i < lines.length) {
      if (lines[i].startsWith("diff --git ")) {
        i = file(lines, i, state);
      } else {
        i++;
      }
    }
    var result = new LinkedHashMap<String, Set<Integer>>();
    for (var p : paths) {
      result.put(p, Set.copyOf(state.getOrDefault(p, new TreeSet<>())));
    }
    return result;
  }

  private record Hunk(
      int oldStart, int oldCount, int newCount, Map<Integer, Integer> moved, List<Integer> added) {
    int oldEnd() {
      return oldCount == 0 ? oldStart : oldStart + oldCount - 1;
    }

    boolean covers(int line) {
      return oldCount > 0 && line >= oldStart && line < oldStart + oldCount;
    }

    int delta() {
      return newCount - oldCount;
    }
  }

  /** Reads one file's diff starting at its {@code diff --git} line; returns the next index. */
  private static int file(String[] lines, int start, Map<String, TreeSet<Integer>> state) {
    String oldPath = null;
    String newPath = null;
    String renameFrom = null;
    String renameTo = null;
    boolean deleted = false;
    boolean created = false;
    int i = start + 1;
    // Extended headers, up to the first hunk or the next file/commit.
    while (i < lines.length && !lines[i].startsWith("@@ ")) {
      var l = lines[i];
      if (l.startsWith("diff --git ") || l.startsWith("commit ")) {
        break;
      }
      if (l.startsWith("rename from ")) {
        renameFrom = unquote(l.substring("rename from ".length()));
      } else if (l.startsWith("rename to ")) {
        renameTo = unquote(l.substring("rename to ".length()));
      } else if (l.startsWith("deleted file mode")) {
        deleted = true;
      } else if (l.startsWith("new file mode")) {
        created = true;
      } else if (l.startsWith("--- ")) {
        oldPath = side(l.substring(4), "a/");
      } else if (l.startsWith("+++ ")) {
        newPath = side(l.substring(4), "b/");
      }
      i++;
    }
    var hunks = new ArrayList<Hunk>();
    while (i < lines.length && lines[i].startsWith("@@ ")) {
      var m = HUNK.matcher(lines[i]);
      if (!m.matches()) {
        break;
      }
      int oldStart = Integer.parseInt(m.group(1));
      int oldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
      int newStart = Integer.parseInt(m.group(3));
      int newCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
      i++;
      var moved = new HashMap<Integer, Integer>();
      var added = new ArrayList<Integer>();
      int o = oldStart;
      int n = newStart;
      while (i < lines.length && (o < oldStart + oldCount || n < newStart + newCount)) {
        var l = lines[i];
        if (l.startsWith("\\")) {
          i++; // "\ No newline at end of file"
          continue;
        }
        char c = l.isEmpty() ? ' ' : l.charAt(0);
        if (c == '+') {
          added.add(n++);
        } else if (c == '-') {
          o++;
        } else {
          moved.put(o++, n++);
        }
        i++;
      }
      while (i < lines.length && lines[i].startsWith("\\")) {
        i++;
      }
      hunks.add(new Hunk(oldStart, oldCount, newCount, moved, added));
    }
    var header = header(lines[start]);
    var from = renameFrom != null ? renameFrom : oldPath != null ? oldPath : header;
    var to = renameTo != null ? renameTo : newPath != null ? newPath : header;
    var before = created || from == null ? new TreeSet<Integer>() : state.remove(from);
    if (before == null) {
      before = new TreeSet<>();
    }
    if (deleted || to == null) {
      return i;
    }
    var after = new TreeSet<Integer>();
    for (int line : before) {
      int mapped = map(line, hunks);
      if (mapped > 0) {
        after.add(mapped);
      }
    }
    hunks.forEach(h -> after.addAll(h.added()));
    state.put(to, after);
    return i;
  }

  /** The new-side number of old line {@code line}, or -1 when a hunk deleted it. */
  private static int map(int line, List<Hunk> hunks) {
    int shift = 0;
    for (var h : hunks) {
      if (h.covers(line)) {
        return h.moved().getOrDefault(line, -1);
      }
      if (line > h.oldEnd()) {
        shift += h.delta();
      }
    }
    return line + shift;
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
