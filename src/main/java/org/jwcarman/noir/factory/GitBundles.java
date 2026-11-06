package org.jwcarman.noir.factory;

import java.nio.file.Path;
import java.util.List;
import org.jwcarman.noir.deliver.BundleRepo;
import org.jwcarman.noir.deliver.FileChange;
import org.jwcarman.noir.exec.CommandRunner;

/** {@link Bundles} backed by a fresh noir-owned bare repository per read ({@link BundleRepo}). */
public final class GitBundles implements Bundles {
  private final CommandRunner runner;
  private final Path tempRoot;

  public GitBundles(CommandRunner runner, Path tempRoot) {
    this.runner = runner;
    this.tempRoot = tempRoot.toAbsolutePath();
  }

  @Override
  public List<String> commitsSince(Path bundle, String baseSha, String branch) {
    try (var repo = BundleRepo.open(runner, bundle, tempRoot)) {
      return repo.commitsSince(baseSha, branch);
    }
  }

  @Override
  public String patchSince(Path bundle, String baseSha, String branch) {
    try (var repo = BundleRepo.open(runner, bundle, tempRoot)) {
      return repo.patchSince(baseSha, branch);
    }
  }

  @Override
  public String diffSince(Path bundle, String baseSha, String branch, List<String> paths) {
    if (paths.isEmpty()) {
      return "";
    }
    try (var repo = BundleRepo.open(runner, bundle, tempRoot)) {
      return repo.diffSince(baseSha, branch, paths);
    }
  }

  @Override
  public List<FileChange> fileChanges(Path bundle, String baseSha, String branch) {
    try (var repo = BundleRepo.open(runner, bundle, tempRoot)) {
      return repo.fileChanges(baseSha, branch);
    }
  }

  @Override
  public String tip(Path bundle, String branch) {
    try (var repo = BundleRepo.open(runner, bundle, tempRoot)) {
      return repo.tip(branch);
    }
  }
}
