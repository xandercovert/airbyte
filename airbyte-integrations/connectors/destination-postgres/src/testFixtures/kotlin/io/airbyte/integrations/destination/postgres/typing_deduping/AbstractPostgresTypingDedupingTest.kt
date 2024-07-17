/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */
package io.airbyte.integrations.destination.postgres.typing_deduping

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.collect.ImmutableMap
import io.airbyte.cdk.db.JdbcCompatibleSourceOperations
import io.airbyte.cdk.integrations.base.JavaBaseConstants
import io.airbyte.cdk.integrations.standardtest.destination.typing_deduping.JdbcTypingDedupingTest
import io.airbyte.commons.json.Jsons.deserialize
import io.airbyte.commons.json.Jsons.jsonNode
import io.airbyte.commons.text.Names.toAlphanumericAndUnderscore
import io.airbyte.integrations.base.destination.typing_deduping.SqlGenerator
import io.airbyte.integrations.destination.postgres.PostgresSQLNameTransformer
import io.airbyte.protocol.models.v0.*
import java.util.*
import java.util.function.Function
import java.util.stream.Collectors
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

abstract class AbstractPostgresTypingDedupingTest : JdbcTypingDedupingTest() {
    private fun generateBigString(): String {
        // Generate exactly 2 chars over the limit
        val length = DEFAULT_VARCHAR_LIMIT_IN_JDBC_GEN + 2
        return RANDOM.ints('a'.code, 'z'.code + 1)
            .limit(length.toLong())
            .collect(
                { StringBuilder() },
                { obj: java.lang.StringBuilder, codePoint: Int -> obj.appendCodePoint(codePoint) },
                { obj: java.lang.StringBuilder, s: java.lang.StringBuilder? -> obj.append(s) }
            )
            .toString()
    }

    override val sqlGenerator: SqlGenerator
        get() = PostgresSqlGenerator(PostgresSQLNameTransformer(), false)

    override val sourceOperations: JdbcCompatibleSourceOperations<*>
        get() = PostgresSourceOperations()

    @Test
    @Throws(Exception::class)
    fun testMixedCasedSchema() {
        streamName = "MixedCaseSchema$streamName"
        val catalog =
            ConfiguredAirbyteCatalog()
                .withStreams(
                    java.util.List.of(
                        ConfiguredAirbyteStream()
                            .withSyncMode(SyncMode.FULL_REFRESH)
                            .withDestinationSyncMode(DestinationSyncMode.OVERWRITE)
                            .withStream(
                                AirbyteStream()
                                    .withNamespace(streamNamespace)
                                    .withName(streamName)
                                    .withJsonSchema(schema)
                            )
                            .withMinimumGenerationId(0L)
                            .withSyncId(13L)
                            .withGenerationId(43L)
                    )
                )

        // First sync
        val messages1 = readMessages("dat/sync1_messages.jsonl")

        runSync(catalog, messages1)

        val expectedRawRecords1 = readRecords("dat/sync1_expectedrecords_raw.jsonl")
        val expectedFinalRecords1 = readRecords("dat/sync1_expectedrecords_nondedup_final.jsonl")
        verifySyncResult(expectedRawRecords1, expectedFinalRecords1, disableFinalTableComparison())
    }

    @Test
    @Throws(Exception::class)
    open fun testMixedCaseRawTableV1V2Migration() {
        streamName = "Mixed Case Table$streamName"
        val catalog =
            ConfiguredAirbyteCatalog()
                .withStreams(
                    java.util.List.of(
                        ConfiguredAirbyteStream()
                            .withSyncMode(SyncMode.FULL_REFRESH)
                            .withDestinationSyncMode(DestinationSyncMode.APPEND)
                            .withStream(
                                AirbyteStream()
                                    .withNamespace(streamNamespace)
                                    .withName(streamName)
                                    .withJsonSchema(schema)
                            )
                            .withGenerationId(43L)
                            .withMinimumGenerationId(0L)
                            .withSyncId(13L)
                    )
                )

        // First sync
        val messages1 = readMessages("dat/sync1_messages.jsonl")

        runSync(catalog, messages1, "airbyte/destination-postgres:0.6.3", Function.identity(), null)
        // Special case to retrieve raw records pre DV2 using the same logic as actual code.
        val rawTableName =
            "_airbyte_raw_" + toAlphanumericAndUnderscore(streamName).lowercase(Locale.getDefault())
        val rawActualRecords =
            database!!.queryJsons(DSL.selectFrom(DSL.name(streamNamespace, rawTableName)).sql)
        // Just verify the size of raw pre DV2, postgres was lower casing the MixedCaseSchema so
        // above
        // retrieval should give 5 records from sync1
        Assertions.assertEquals(5, rawActualRecords.size)
        val messages2 = readMessages("dat/sync2_messages.jsonl")
        runSync(catalog, messages2)
        val expectedRawRecords2 = readRecords("dat/sync2_mixedcase_expectedrecords_raw.jsonl")
        val expectedFinalRecords2 =
            readRecords("dat/sync2_mixedcase_expectedrecords_fullrefresh_append_final.jsonl")
        verifySyncResult(expectedRawRecords2, expectedFinalRecords2, disableFinalTableComparison())
    }

    @Test
    @Throws(Exception::class)
    fun testRawTableMetaMigration_append() {
        val catalog1 =
            ConfiguredAirbyteCatalog()
                .withStreams(
                    java.util.List.of(
                        ConfiguredAirbyteStream()
                            .withSyncMode(SyncMode.FULL_REFRESH)
                            .withDestinationSyncMode(DestinationSyncMode.APPEND)
                            .withStream(
                                AirbyteStream()
                                    .withNamespace(streamNamespace)
                                    .withName(streamName)
                                    .withJsonSchema(schema)
                            )
                    )
                )

        // First sync without _airbyte_meta
        val messages1 = readMessages("dat/sync1_messages.jsonl")
        runSync(
            catalog1,
            messages1,
            "airbyte/destination-postgres:2.0.4",
            Function.identity(),
            null
        )
        // Second sync
        val catalog2 =
            ConfiguredAirbyteCatalog()
                .withStreams(
                    java.util.List.of(
                        ConfiguredAirbyteStream()
                            .withSyncMode(SyncMode.FULL_REFRESH)
                            .withDestinationSyncMode(DestinationSyncMode.APPEND)
                            .withStream(
                                AirbyteStream()
                                    .withNamespace(streamNamespace)
                                    .withName(streamName)
                                    .withJsonSchema(schema)
                            )
                            .withMinimumGenerationId(0L)
                            .withSyncId(13L)
                            .withGenerationId(42L)
                    )
                )
        val messages2 = readMessages("dat/sync2_messages_after_meta.jsonl")
        runSync(catalog2, messages2)

        val expectedRawRecords2 = readRecords("dat/sync2_expectedrecords_mixed_meta_raw.jsonl")
        val expectedFinalRecords2 =
            readRecords("dat/sync2_expectedrecords_fullrefresh_append_mixed_meta_final.jsonl")
        verifySyncResult(expectedRawRecords2, expectedFinalRecords2, disableFinalTableComparison())
    }

    @Test
    @Throws(Exception::class)
    fun testRawTableMetaMigration_incrementalDedupe() {
        val catalog1 =
            ConfiguredAirbyteCatalog()
                .withStreams(
                    java.util.List.of(
                        ConfiguredAirbyteStream()
                            .withSyncMode(SyncMode.INCREMENTAL)
                            .withCursorField(listOf("updated_at"))
                            .withDestinationSyncMode(DestinationSyncMode.APPEND_DEDUP)
                            .withPrimaryKey(java.util.List.of(listOf("id1"), listOf("id2")))
                            .withStream(
                                AirbyteStream()
                                    .withNamespace(streamNamespace)
                                    .withName(streamName)
                                    .withJsonSchema(schema)
                            )
                    )
                )

        // First sync without _airbyte_meta
        val messages1 = readMessages("dat/sync1_messages.jsonl")
        runSync(
            catalog1,
            messages1,
            "airbyte/destination-postgres:2.0.4",
            Function.identity(),
            null
        )
        // Second sync
        val catalog2 =
            ConfiguredAirbyteCatalog()
                .withStreams(
                    java.util.List.of(
                        ConfiguredAirbyteStream()
                            .withSyncMode(SyncMode.INCREMENTAL)
                            .withCursorField(listOf("updated_at"))
                            .withDestinationSyncMode(DestinationSyncMode.APPEND_DEDUP)
                            .withPrimaryKey(java.util.List.of(listOf("id1"), listOf("id2")))
                            .withStream(
                                AirbyteStream()
                                    .withNamespace(streamNamespace)
                                    .withName(streamName)
                                    .withJsonSchema(schema)
                            )
                            .withMinimumGenerationId(0L)
                            .withSyncId(13L)
                            .withGenerationId(42L)
                    )
                )
        val messages2 = readMessages("dat/sync2_messages_after_meta.jsonl")
        runSync(catalog2, messages2)

        val expectedRawRecords2 = readRecords("dat/sync2_expectedrecords_mixed_meta_raw.jsonl")
        val expectedFinalRecords2 =
            readRecords("dat/sync2_expectedrecords_incremental_dedup_meta_final.jsonl")
        verifySyncResult(expectedRawRecords2, expectedFinalRecords2, disableFinalTableComparison())
    }

    @Throws(Exception::class)
    override fun dumpRawTableRecords(streamNamespace: String?, streamName: String): List<JsonNode> {
        return super.dumpRawTableRecords(streamNamespace, streamName.lowercase(Locale.getDefault()))
    }

    @Test
    @Throws(Exception::class)
    open fun testVarcharLimitOver64K() {
        val catalog =
            ConfiguredAirbyteCatalog()
                .withStreams(
                    java.util.List.of(
                        ConfiguredAirbyteStream()
                            .withSyncMode(SyncMode.FULL_REFRESH)
                            .withDestinationSyncMode(DestinationSyncMode.OVERWRITE)
                            .withStream(
                                AirbyteStream()
                                    .withNamespace(streamNamespace)
                                    .withName(streamName)
                                    .withJsonSchema(schema)
                            )
                            .withMinimumGenerationId(0L)
                            .withSyncId(13L)
                            .withGenerationId(42L)
                    )
                )

        val message = AirbyteMessage()
        val largeString = generateBigString()
        val data: Map<String, Any> =
            ImmutableMap.of<String, Any>(
                "id1",
                1,
                "id2",
                200,
                "updated_at",
                "2021-01-01T00:00:00Z",
                "name",
                largeString
            )
        message.type = AirbyteMessage.Type.RECORD
        message.record =
            AirbyteRecordMessage()
                .withNamespace(streamNamespace)
                .withStream(streamName)
                .withData(jsonNode(data))
                .withEmittedAt(1000L)
        val messages1: MutableList<AirbyteMessage> = ArrayList()
        messages1.add(message)
        runSync(catalog, messages1)

        // Only assert on the large varchar string landing in final table.
        // Rest of the fields' correctness is tested by other means in other tests.
        val actualFinalRecords = dumpFinalTableRecords(streamNamespace, streamName)
        Assertions.assertEquals(1, actualFinalRecords.size)
        Assertions.assertEquals(largeString, actualFinalRecords[0]["name"].asText())
    }

    @Test
    @Throws(Exception::class)
    fun testAirbyteMetaAndGenerationIdMigration() {
        val catalog =
            ConfiguredAirbyteCatalog()
                .withStreams(
                    java.util.List.of(
                        ConfiguredAirbyteStream()
                            .withSyncMode(SyncMode.FULL_REFRESH)
                            .withDestinationSyncMode(DestinationSyncMode.APPEND)
                            .withSyncId(42L)
                            .withGenerationId(43L)
                            .withMinimumGenerationId(0L)
                            .withStream(
                                AirbyteStream()
                                    .withNamespace(streamNamespace)
                                    .withName(streamName)
                                    .withJsonSchema(schema)
                            )
                    )
                )

        // First sync
        val messages1 = readMessages("dat/sync1_messages.jsonl")
        runSync(
            catalog,
            messages1,
            "airbyte/destination-postgres:2.0.15",
            Function.identity(),
            null
        )

        // Second sync
        val messages2 = readMessages("dat/sync2_messages.jsonl")
        runSync(catalog, messages2)
        val actualRawRecords1 = dumpRawTableRecords(streamNamespace, streamName)
        val loadedAtValues1 =
            actualRawRecords1
                .stream()
                .map { record: JsonNode -> record[JavaBaseConstants.COLUMN_NAME_AB_LOADED_AT] }
                .collect(Collectors.toSet())
        Assertions.assertEquals(
            1,
            loadedAtValues1.size,
            "Expected only 1 value for loaded_at"
        )

        // The first 5 records in these files were written by the old version, and have
        // several differences with the new records:
        // In raw tables: _airbyte_generation_id at all. _airbyte_meta only contains the changes
        // field
        // In final tables: no generation ID, and airbyte_meta still uses the old `{errors: [...]}`
        // structure
        // So modify the expected records to reflect those differences.
        val expectedRawRecords2 = readRecords("dat/sync2_expectedrecords_raw.jsonl")
        for (i in 0..4) {
            val record = expectedRawRecords2[i] as ObjectNode
            val originalChanges =
                record[JavaBaseConstants.COLUMN_NAME_AB_META]["changes"].toString()
            record.set<JsonNode>(
                JavaBaseConstants.COLUMN_NAME_AB_META,
                deserialize("{\"changes\":$originalChanges}")
            )
            record.remove(JavaBaseConstants.COLUMN_NAME_AB_GENERATION_ID)
        }
        val expectedFinalRecords2 =
            readRecords("dat/sync2_expectedrecords_fullrefresh_append_final.jsonl")
        for (i in 0..4) {
            val record = expectedFinalRecords2[i] as ObjectNode
            record.remove(JavaBaseConstants.COLUMN_NAME_AB_GENERATION_ID)
        }
        verifySyncResult(expectedRawRecords2, expectedFinalRecords2, disableFinalTableComparison())

        // Verify that we didn't trigger a soft reset.
        // There should be two unique loaded_at values in the raw table.
        // (only do this if T+D is enabled to begin with; otherwise loaded_at will just be null)
        if (!disableFinalTableComparison()) {
            val actualRawRecords2 = dumpRawTableRecords(streamNamespace, streamName)
            val loadedAtValues2 =
                actualRawRecords2
                    .stream()
                    .map { record: JsonNode -> record[JavaBaseConstants.COLUMN_NAME_AB_LOADED_AT] }
                    .collect(Collectors.toSet())
            Assertions.assertEquals(
                2,
                loadedAtValues2.size,
                "Expected two different values for loaded_at. If there is only 1 value, then we incorrectly triggered a soft reset. If there are more than 2, then something weird happened?"
            )
            Assertions.assertTrue(
                loadedAtValues2.containsAll(loadedAtValues1),
                "expected the loaded_at value from 1st sync to still be present in the final table, otherwise we triggered a soft reset"
            )
        }
    }

    @Test
    @Throws(Exception::class)
    fun testAirbyteMetaAndGenerationIdMigrationForOverwrite() {
        val catalog =
            ConfiguredAirbyteCatalog()
                .withStreams(
                    java.util.List.of(
                        ConfiguredAirbyteStream()
                            .withSyncMode(SyncMode.FULL_REFRESH)
                            .withDestinationSyncMode(DestinationSyncMode.OVERWRITE)
                            .withSyncId(42L)
                            .withGenerationId(43L)
                            .withMinimumGenerationId(43L)
                            .withStream(
                                AirbyteStream()
                                    .withNamespace(streamNamespace)
                                    .withName(streamName)
                                    .withJsonSchema(schema)
                            )
                    )
                )

        // First sync
        val messages1 = readMessages("dat/sync1_messages.jsonl")
        runSync(
            catalog,
            messages1,
            "airbyte/destination-postgres:2.0.15",
            Function.identity(),
            null
        )

        // Second sync
        val messages2 = readMessages("dat/sync2_messages.jsonl")
        runSync(catalog, messages2)

        val expectedRawRecords2 = readRecords("dat/sync2_expectedrecords_overwrite_raw.jsonl")
        val expectedFinalRecords2 =
            readRecords("dat/sync2_expectedrecords_fullrefresh_overwrite_final.jsonl")
        verifySyncResult(expectedRawRecords2, expectedFinalRecords2, disableFinalTableComparison())
    }

    companion object {
        private const val DEFAULT_VARCHAR_LIMIT_IN_JDBC_GEN = 65535

        private val RANDOM = Random()
    }
}
