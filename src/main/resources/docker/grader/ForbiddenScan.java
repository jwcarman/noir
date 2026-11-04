// noir grader pre-scan. Usage: java ForbiddenScan.java <classesDir> <depsDir> <outFile>
//
// Lists everything in the builder's classes and dependency jars that could subvert grading:
// holdout lookalikes, JUnit service registrations, a root junit-platform.properties (each also under
// a multi-release META-INF/versions/<N>/ prefix), manifests with Class-Path (they pull in archives
// this scan never sees), odd manifest names, symlinks, and anything in the deps directory that is
// not a plainly named regular .jar file.
//
// One line per problem is written to <outFile>; the exit code is 0 once the file is written.
// grade.sh runs the tests only if this exits 0 and leaves an empty file. The scan fails closed:
// anything unexpected becomes a problem line. It only reads files and zip entries and never loads
// builder classes.
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

public class ForbiddenScan {
  static final Pattern JAR_NAME = Pattern.compile("[A-Za-z0-9._-]+\\.jar");
  // A multi-release jar serves META-INF/versions/<N>/x as x, so rules apply to the stripped name.
  static final Pattern VERSIONED = Pattern.compile("(?i)meta-inf/versions/[0-9]+/");
  static final List<String> problems = new ArrayList<>();

  public static void main(String[] args) throws IOException {
    var out = Path.of(args[2]);
    try {
      scanClasses(Path.of(args[0]));
      scanDeps(Path.of(args[1]));
    } catch (Throwable t) {
      problems.add("scan failed: " + t);
    }
    Files.write(out, problems, StandardCharsets.UTF_8);
  }

  /**
   * The reason an entry path (relative, '/'-separated) is forbidden, or null. A leading
   * META-INF/versions/<digits>/ (any case) and any trailing '/' are ignored, in jars and the
   * classes tree alike.
   */
  static String forbiddenPath(String path) {
    var m = VERSIONED.matcher(path);
    var p = (m.lookingAt() ? path.substring(m.end()) : path).toLowerCase(Locale.ROOT);
    // ZipFile.getEntry(x) also matches an entry named x/, which can hold data: drop trailing '/'.
    p = p.replaceAll("/+$", "");
    if (p.equals("noir/holdout") || p.startsWith("noir/holdout/")) {
      return "holdout lookalike";
    }
    if (p.startsWith("meta-inf/services/org.junit.")) {
      return "JUnit service registration";
    }
    if (p.equals("junit-platform.properties")) {
      return "junit-platform.properties";
    }
    return null;
  }

  static void scanClasses(Path root) throws IOException {
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      problems.add(root + ": classes directory missing or not a directory");
      return;
    }
    Files.walkFileTree(
        root,
        new java.nio.file.SimpleFileVisitor<Path>() {
          @Override
          public java.nio.file.FileVisitResult preVisitDirectory(
              Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
            check(dir);
            return java.nio.file.FileVisitResult.CONTINUE;
          }

          @Override
          public java.nio.file.FileVisitResult visitFile(
              Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
            check(file);
            return java.nio.file.FileVisitResult.CONTINUE;
          }

          @Override
          public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException e) {
            problems.add(file + ": unreadable (" + e + ")");
            return java.nio.file.FileVisitResult.CONTINUE;
          }

          void check(Path p) {
            if (Files.isSymbolicLink(p)) {
              problems.add(p + ": symlink");
            }
            if (p.equals(root)) {
              return;
            }
            var rel = root.relativize(p).toString().replace('\\', '/');
            var why = forbiddenPath(rel);
            if (why != null) {
              problems.add(p + ": " + why);
            }
          }
        });
  }

  static void scanDeps(Path deps) throws IOException {
    if (!Files.exists(deps, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    List<Path> entries;
    try (var s = Files.list(deps)) {
      entries = s.sorted().toList();
    }
    for (var p : entries) {
      var name = p.getFileName().toString();
      if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || !JAR_NAME.matcher(name).matches()) {
        problems.add(p + ": not a plain jar file");
        continue;
      }
      scanJar(p);
    }
  }

  static void scanJar(Path jar) {
    try (var jf = new JarFile(jar.toFile(), false)) {
      var manifest = jf.getManifest();
      if (manifest != null && manifest.getMainAttributes().containsKey(Attributes.Name.CLASS_PATH)) {
        problems.add(jar + ": manifest has Class-Path");
      }
      var entries = jf.entries();
      while (entries.hasMoreElements()) {
        var name = entries.nextElement().getName();
        var why = forbiddenPath(name);
        if (why != null) {
          problems.add(jar + ": " + name + " (" + why + ")");
        }
        if (name.toLowerCase(Locale.ROOT).equals("meta-inf/manifest.mf")
            && !name.equals("META-INF/MANIFEST.MF")) {
          problems.add(jar + ": " + name + " (manifest-like entry with unusual name)");
        }
      }
    } catch (Throwable t) {
      problems.add(jar + ": cannot be scanned (" + t + ")");
    }
  }
}
