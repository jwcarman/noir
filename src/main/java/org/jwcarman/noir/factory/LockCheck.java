package org.jwcarman.noir.factory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jwcarman.noir.deliver.FileChange;
import org.jwcarman.noir.spec.Spec;

/**
 * Rejects an attempt that changes an existing file the spec does not allow. Only the files in
 * {@code modifies} and {@code testsToUpdate} may be modified, and none may be deleted or renamed.
 * New files are allowed, except build and test-runtime configuration that could stop locked tests
 * from failing the build ({@code .mvn/**}, {@code src/test/resources/junit-platform.properties} and
 * {@code src/test/resources/META-INF/services/**}). Applies to every spec.
 */
final class LockCheck {

  private static final String TEST_RESOURCES = "src/test/resources/";

  private LockCheck() {}

  /** An added file that could change how Maven or JUnit runs the locked tests. */
  static boolean lockedNewFile(String path) {
    return path.startsWith(".mvn/")
        || path.equals(TEST_RESOURCES + "junit-platform.properties")
        || path.startsWith(TEST_RESOURCES + "META-INF/services/");
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
    sb.append(allowed.isEmpty() ? "new files" : list(allowed) + ", and new files");
    sb.append(". Restore those files and make the change another way.");
    return Optional.of(sb.toString());
  }

  private static String list(Iterable<String> paths) {
    Set<String> unique = new LinkedHashSet<>();
    paths.forEach(unique::add);
    return unique.stream().map(p -> "`" + p + "`").collect(Collectors.joining(", "));
  }
}
