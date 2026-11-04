package org.jwcarman.noir.spec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Test fixtures for {@link Spec}. */
public final class Specs {
    public static final String ID = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W";

    private Specs() {}

    public static Spec valid() {
        return new Spec(
                1,
                ID,
                "jwcarman/demo",
                "0123456789abcdef0123456789abcdef01234567",
                "Add a bounded cache",
                "Callers need a size-bounded LRU cache.",
                List.of(
                        new Requirement("R1", "get returns the value last put for a key",
                                List.of(new Example("an empty cache", "put(\"a\", 1) then get(\"a\")", "returns 1"))),
                        new Requirement("R2", "put throws IllegalArgumentException when key is blank",
                                List.of(new Example("an empty cache", "put(\"\", 1)", "throws IllegalArgumentException")))),
                List.of("no new dependencies"),
                new QualityBar(0.80, 0.70),
                List.of("src/main/java/com/example/api/Foo.java"));
    }

    public static Spec withRequirementIds(Spec spec, String... ids) {
        var reqs = new ArrayList<Requirement>();
        for (int i = 0; i < ids.length; i++) {
            var r = spec.requirements().get(i);
            reqs.add(new Requirement(ids[i], r.statement(), r.examples()));
        }
        return spec.withRequirements(reqs);
    }

    public static Spec withNoExamplesOn(Spec spec, String reqId) {
        return spec.withRequirements(spec.requirements().stream()
                .map(r -> r.id().equals(reqId) ? new Requirement(r.id(), r.statement(), List.of()) : r)
                .toList());
    }

    static List<String> list(String... s) {
        return Arrays.asList(s);
    }
}
