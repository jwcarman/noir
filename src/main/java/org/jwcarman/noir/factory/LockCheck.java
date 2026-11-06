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
 * {@code modifies} and {@code testsToUpdate} may change; new files are always allowed. Applies to
 * every spec.
 */
final class LockCheck {

  private LockCheck() {}

  /** The builder feedback for the locked files the attempt changed, or empty if it changed none. */
  static Optional<String> violation(Spec spec, List<FileChange> changes) {
    var allowed = new LinkedHashSet<String>();
    allowed.addAll(spec.modifies());
    allowed.addAll(spec.testsToUpdate());
    var modified = new ArrayList<String>();
    var deleted = new ArrayList<String>();
    for (var c : changes) {
      switch (c.status()) {
        case ADDED -> {}
        case MODIFIED -> {
          if (!allowed.contains(c.path())) {
            modified.add(c.path());
          }
        }
        case DELETED -> {
          if (!allowed.contains(c.path())) {
            deleted.add(c.path());
          }
        }
        case RENAMED -> {
          if (!allowed.contains(c.oldPath()) || !allowed.contains(c.path())) {
            deleted.add(c.oldPath());
          }
        }
      }
    }
    if (modified.isEmpty() && deleted.isEmpty()) {
      return Optional.empty();
    }
    var clauses = new ArrayList<String>();
    if (!modified.isEmpty()) {
      clauses.add("modified " + list(modified));
    }
    if (!deleted.isEmpty()) {
      clauses.add("deleted " + list(deleted));
    }
    var sb = new StringBuilder("You ").append(String.join(" and ", clauses)).append(". ");
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
