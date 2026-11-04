package org.jwcarman.noir.spec;

import java.util.List;

/** A numbered behavioral requirement ({@code R1}, {@code R2}, ...). */
public record Requirement(String id, String statement, List<Example> examples) {
    public Requirement {
        examples = examples == null ? List.of() : List.copyOf(examples);
    }
}
