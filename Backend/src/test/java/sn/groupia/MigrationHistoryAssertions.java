package sn.groupia;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import liquibase.changelog.ChangeLogParameters;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.springframework.jdbc.core.JdbcTemplate;

/** Compares persisted history, never recalculates or writes historical checksums. */
final class MigrationHistoryAssertions {
    private static final String MASTER = "db/changelog/db.changelog-master.yaml";

    record Identity(String id, String author, String filename) {}

    record Entry(Identity identity, String checksum, int executionOrder, String executionType) {}

    private MigrationHistoryAssertions() {}

    /** Explicitly validate the additive nullable field, then compare every old column unchanged. */
    static java.util.Map<String, Object> historicalOrder(java.util.Map<String, Object> row) {
        assertThat(row).containsKey("cancellation_case_id");
        assertThat(row.remove("cancellation_case_id"))
                .as("Historical orders must not acquire an invented cancellation case")
                .isNull();
        return row;
    }

    /** Ensure new hosted-checkout fields have no invented values for historical payments. */
    static java.util.Map<String, Object> historicalPayment(java.util.Map<String, Object> row) {
        for (String column : List.of("provider_collection_fee", "provider_customer_charged")) {
            assertThat(row).containsKey(column);
            assertThat(row.remove(column))
                    .as("Historical payments must not acquire invented %s", column)
                    .isNull();
        }
        return row;
    }

    static List<Entry> snapshot(JdbcTemplate jdbc) {
        return jdbc.query(
                "select id, author, filename, md5sum, orderexecuted, exectype from databasechangelog order by orderexecuted",
                (rs, row) ->
                        new Entry(
                                new Identity(
                                        rs.getString("id"),
                                        rs.getString("author"),
                                        rs.getString("filename")),
                                rs.getString("md5sum"),
                                rs.getInt("orderexecuted"),
                                rs.getString("exectype")));
    }

    static void assertPreserved(JdbcTemplate jdbc, List<Entry> before) throws Exception {
        try (var resources = new ClassLoaderResourceAccessor()) {
            var changelog =
                    ChangeLogParserFactory.getInstance()
                            .getParser(MASTER, resources)
                            .parse(MASTER, new ChangeLogParameters(), resources);
            var declared =
                    changelog.getChangeSets().stream()
                            .map(
                                    change ->
                                            new Identity(
                                                    change.getId(),
                                                    change.getAuthor(),
                                                    change.getFilePath()))
                            .collect(Collectors.toSet());
            assertPreserved(before, snapshot(jdbc), declared);
        }
    }

    static void assertPreserved(List<Entry> before, List<Entry> after, Set<Identity> declared) {
        assertThat(before).as("Historical baseline must not be empty").isNotEmpty();
        assertThat(before).extracting(Entry::identity).doesNotHaveDuplicates();
        assertThat(after).extracting(Entry::identity).doesNotHaveDuplicates();
        var historical = before.stream().map(Entry::identity).collect(Collectors.toSet());
        assertThat(after.stream().filter(row -> historical.contains(row.identity())).toList())
                .as("Every historical identity, checksum and execution record must be preserved")
                .containsExactlyElementsOf(before);
        assertThat(after)
                .allSatisfy(
                        row -> {
                            assertThat(declared)
                                    .as(
                                            "Only changesets declared by the current master are legitimate")
                                    .contains(row.identity());
                            assertThat(row.checksum())
                                    .as("Persisted checksum for %s", row.identity())
                                    .isNotBlank();
                        });
    }
}
