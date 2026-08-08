package org.yinwang.yin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalCorpusTest {

    private static final Map<String, String> MIGRATED = Map.of(
            "array.yin", "tests/empty-vector.yin",
            "test4.yin", "tests/arithmetic.yin"
    );

    private static final Set<String> ARCHIVED = Set.of(
            "assign1.yin",
            "attr.yin",
            "attr2.yin",
            "preparser.yin",
            "test1.yin",
            "test2.yin",
            "test3.yin",
            "typecheck1.elt",
            "types.yin",
            "types2.yin",
            "types3.yin"
    );

    @Test
    void everyHistoricalProgramHasAnExplicitClassification() throws Exception {
        Set<String> actual;
        try (var files = Files.list(Path.of("experiments"))) {
            actual = files
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".yin") || name.endsWith(".elt"))
                    .collect(Collectors.toSet());
        }

        Set<String> classified = new java.util.HashSet<>(ARCHIVED);
        classified.addAll(MIGRATED.keySet());
        assertEquals(actual, classified);
    }

    @Test
    void migratedProgramsHaveMaintainedRunnableSuccessors() {
        for (String target : MIGRATED.values()) {
            assertTrue(Files.isRegularFile(Path.of(target)), target);
            assertTrue(new Interpreter(target).interp(target).toString().length() > 0);
            assertTrue(new TypeChecker(target).typecheck(target).toString().length() > 0);
        }
    }
}
