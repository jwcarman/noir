package org.jwcarman.noir.factory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jwcarman.noir.deliver.FileChange;
import org.jwcarman.noir.spec.Spec;

/**
 * Rejects an attempt that changes an existing file the spec does not allow. Only the files in
 * {@code modifies} and {@code testsToUpdate} may be modified, and none may be deleted or renamed.
 * New files are allowed, except build and test-runtime configuration that could stop locked tests
 * from failing the build: anything under {@code .mvn/}, and, anywhere in the repository (JUnit
 * reads them from the whole test classpath, main resources included), any file named {@code
 * junit-platform.properties} and any JUnit service registration ({@code
 * META-INF/services/org.junit.*}). Names are matched ignoring case. Applies to every spec.
 */
final class LockCheck {

  private LockCheck() {}

  /** An added file that could change how Maven or JUnit runs the locked tests. */
  static boolean lockedNewFile(String path) {
    var p = path.toLowerCase(Locale.ROOT);
    var name = p.substring(p.lastIndexOf('/') + 1);
    return p.startsWith(".mvn/")
        || name.equals("junit-platform.properties")
        || p.contains("meta-inf/services/org.junit.");
  }

  /** The builder feedback for the locked files the attempt changed, or empty if it changed none. */
  static Optional<String> violation(Spec spec, List<FileChange> changes) {
    var allowed = new LinkedHashSet<String>();
    allowed.addAll(spec.modifies());
    allowed.addAll(spec.testsToUpdate());
    var modified = new ArrayList<String>();
    var added = new ArrayList<String>();
    var deleted = new ArrayList<String>();
    for (var c : changes) {
      switch (c.status()) {
        case ADDED -> {
          if (lockedNewFile(c.path())) {
            added.add(c.path());
          }
        }
        case MODIFIED -> {
          if (!allowed.contains(c.path())) {
            modified.add(c.path());
          }
        }
        case DELETED -> deleted.add(c.path());
        case RENAMED -> deleted.add(c.oldPath());
      }
    }
    if (modified.isEmpty() && added.isEmpty() && deleted.isEmpty()) {
      return Optional.empty();
    }
    var clauses = new ArrayList<String>();
    if (!modified.isEmpty()) {
      clauses.add("modified " + list(modified));
    }
    if (!added.isEmpty()) {
      clauses.add("added " + list(added));
    }
    if (!deleted.isEmpty()) {
      clauses.add("deleted " + list(deleted));
    }
    var last = clauses.removeLast();
    var joined = clauses.isEmpty() ? last : String.join(", ", clauses) + " and " + last;
    var sb = new StringBuilder("You ").append(joined).append(". ");
    sb.append("This spec allows changes only to: ");
    // When the attempt added a locked new file, "and new files" alone would contradict the
    // feedback, so the exceptions are named.
    var newFiles =
        added.isEmpty()
            ? "new files"
            : "new files other than `.mvn/` files, `junit-platform.properties` files and JUnit"
                + " service registrations (`META-INF/services/org.junit.*`)";
    sb.append(allowed.isEmpty() ? newFiles : list(allowed) + ", and " + newFiles);
    sb.append(
        added.isEmpty()
            ? ". Restore those files and make the change another way."
            : ". Undo those changes and make the change another way.");
    return Optional.of(sb.toString());
  }

  private static String list(Iterable<String> paths) {
    Set<String> unique = new LinkedHashSet<>();
    paths.forEach(unique::add);
    return unique.stream().map(p -> "`" + p + "`").collect(Collectors.joining(", "));
  }
}
