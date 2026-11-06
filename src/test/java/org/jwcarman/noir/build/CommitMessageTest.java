package org.jwcarman.noir.build;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommitMessageTest {

  @Test
  void firstAttemptIsTheTitleAndIssue() {
    assertThat(CommitMessage.of(5, "Add a Slugifier that turns a title into a URL slug", 1))
        .isEqualTo("Add a Slugifier that turns a title into a URL slug (#5)");
  }

  @Test
  void laterAttemptsAreNumbered() {
    assertThat(CommitMessage.of(5, "Add a Slugifier", 2))
        .isEqualTo("Add a Slugifier (#5), attempt 2");
  }

  @Test
  void missingIssueNumberIsLeftOut() {
    assertThat(CommitMessage.of(null, "Add a Slugifier", 1)).isEqualTo("Add a Slugifier");
  }

  @Test
  void controlCharactersAndWhitespaceRunsBecomeOneSpace() {
    assertThat(CommitMessage.of(5, "  Add\na\t\u0000Slugifier\r\n ", 1))
        .isEqualTo("Add a Slugifier (#5)");
  }

  @Test
  void blankTitleFallsBack() {
    assertThat(CommitMessage.of(5, " \n ", 1)).isEqualTo("noir work (#5)");
    assertThat(CommitMessage.of(5, null, 1)).isEqualTo("noir work (#5)");
  }

  @Test
  void longTitlesAreCutWithoutSplittingASurrogatePair() {
    var title = "a".repeat(CommitMessage.MAX_TITLE - 1) + "😀tail";
    assertThat(CommitMessage.of(5, title, 1))
        .isEqualTo("a".repeat(CommitMessage.MAX_TITLE - 1) + "… (#5)");
  }
}
