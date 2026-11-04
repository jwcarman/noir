package org.jwcarman.noir.spec;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ItemIdTest {
    @Test
    void newIdIsValidAndPrefixed() {
        var id = ItemId.newId(Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC), new Random(1));
        assertThat(id).startsWith("nr-").hasSize(29);
        assertThat(ItemId.isValid(id)).isTrue();
    }

    @Test
    void idsSortByTime() {
        var a = ItemId.newId(Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC), new Random(1));
        var b = ItemId.newId(Clock.fixed(Instant.ofEpochMilli(2000), ZoneOffset.UTC), new Random(1));
        assertThat(a).isLessThan(b);
    }

    @Test
    void holdoutPackageIsLegalJava() {
        assertThat(ItemId.holdoutPackage("nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W"))
                .isEqualTo("noir.holdout.nr_01jbx4zq8k3v0m7n2p5r6s9t1w");
    }

    @Test
    void rejectsMalformed() {
        assertThat(ItemId.isValid("nr-short")).isFalse();
        assertThat(ItemId.isValid("xx-01JBX4ZQ8K3V0M7N2P5R6S9T1W")).isFalse();
        assertThat(ItemId.isValid("nr-01JBX4ZQ8K3V0M7N2P5R6S9TIU")).isFalse();
        assertThat(ItemId.isValid(null)).isFalse();
    }
}
