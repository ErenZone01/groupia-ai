package sn.groupia;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MigrationHistoryAssertionsTests {
    private static final MigrationHistoryAssertions.Identity OLD = identity("001-old");
    private static final MigrationHistoryAssertions.Identity NEW = identity("030-new");
    private static final MigrationHistoryAssertions.Entry ORIGINAL = entry(OLD, "9:original", 1);
    private static final MigrationHistoryAssertions.Entry ADDITION = entry(NEW, "9:addition", 2);

    @Test
    void acceptsOnlyDeclaredAdditionsWithoutChangingTheHistoricalSnapshot() {
        MigrationHistoryAssertions.assertPreserved(
                List.of(ORIGINAL), List.of(ORIGINAL, ADDITION), Set.of(OLD, NEW));
        MigrationHistoryAssertions.assertPreserved(
                List.of(ORIGINAL), List.of(ORIGINAL), Set.of(OLD, NEW));
    }

    @ParameterizedTest
    @MethodSource("corruptHistory")
    void rejectsMissingModifiedDuplicatedOrUndeclaredHistory(
            List<MigrationHistoryAssertions.Entry> after) {
        assertThatThrownBy(
                        () ->
                                MigrationHistoryAssertions.assertPreserved(
                                        List.of(ORIGINAL), after, Set.of(OLD, NEW)))
                .isInstanceOf(AssertionError.class);
    }

    static Stream<List<MigrationHistoryAssertions.Entry>> corruptHistory() {
        return Stream.of(
                List.of(ADDITION),
                List.of(entry(OLD, "9:rewritten", 1), ADDITION),
                List.of(entry(OLD, null, 1), ADDITION),
                List.of(
                        entry(
                                new MigrationHistoryAssertions.Identity(
                                        OLD.id(), "other", OLD.filename()),
                                "9:original",
                                1)),
                List.of(
                        entry(
                                new MigrationHistoryAssertions.Identity(
                                        OLD.id(), OLD.author(), "other.sql"),
                                "9:original",
                                1)),
                List.of(entry(OLD, "9:original", 2), ADDITION),
                List.of(ORIGINAL, ORIGINAL, ADDITION),
                List.of(ORIGINAL, entry(identity("rogue"), "9:rogue", 2)),
                List.of(ORIGINAL, entry(NEW, null, 2)),
                List.of(new MigrationHistoryAssertions.Entry(OLD, "9:original", 1, "RERAN")));
    }

    private static MigrationHistoryAssertions.Identity identity(String id) {
        return new MigrationHistoryAssertions.Identity(
                id, "groupia", "db/changelog/" + id + ".sql");
    }

    private static MigrationHistoryAssertions.Entry entry(
            MigrationHistoryAssertions.Identity id, String checksum, int order) {
        return new MigrationHistoryAssertions.Entry(id, checksum, order, "EXECUTED");
    }
}
