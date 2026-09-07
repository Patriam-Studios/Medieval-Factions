package com.dansplugins.factionsystem.fixture

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID

internal data class DisposableFixtureReceipt(
    val schemaVersion: Int,
    val tag: String,
    val actorIds: Set<String>,
    val baselineFactionIds: Set<String>
)

/** Immutable owner receipts; closing markers survive failed cleanup and fence replay after restart. */
internal class DisposableFixtureReceiptStore(private val directory: Path) {
    private val gson = Gson()

    fun validate(tag: String, actors: Set<UUID>) {
        require(Regex("PT[0-9a-f]{10}").matches(tag)) { "Fixture tag must be PT followed by 10 lowercase hex digits" }
        require(actors.isNotEmpty() && actors.size <= 64) { "Fixture must contain 1 to 64 exact actor UUIDs" }
    }

    fun read(tag: String): DisposableFixtureReceipt? {
        require(Regex("PT[0-9a-f]{10}").matches(tag)) { "Invalid fixture tag" }
        val file = directory.resolve("$tag.json")
        if (!Files.exists(file)) return null
        val tree = JsonParser.parseString(Files.readString(file)).asJsonObject
        require(tree.keySet() == setOf("schemaVersion", "tag", "actorIds", "baselineFactionIds")) { "Invalid fixture receipt fields" }
        val receipt = gson.fromJson(tree, DisposableFixtureReceipt::class.java)
        require(receipt.schemaVersion == 1 && receipt.tag == tag) { "Unsupported or mismatched fixture receipt" }
        validate(tag, receipt.actorIds.map(UUID::fromString).toSet())
        require(receipt.actorIds.all { UUID.fromString(it).toString() == it }) { "Noncanonical fixture actor UUID" }
        return receipt
    }

    fun create(receipt: DisposableFixtureReceipt) {
        writeNew(directory.resolve("${receipt.tag}.json"), gson.toJson(receipt).toByteArray(Charsets.UTF_8))
    }

    fun markClosing(receipt: DisposableFixtureReceipt) {
        val bytes = Files.readAllBytes(directory.resolve("${receipt.tag}.json"))
        val marker = directory.resolve("${receipt.tag}.closing")
        if (Files.exists(marker)) {
            check(Files.readAllBytes(marker).contentEquals(bytes)) { "Fixture closing receipt differs from its baseline" }
        } else {
            writeNew(marker, bytes)
        }
    }

    fun isClosing(receipt: DisposableFixtureReceipt): Boolean {
        val marker = directory.resolve("${receipt.tag}.closing")
        if (!Files.exists(marker)) return false
        check(Files.readAllBytes(marker).contentEquals(Files.readAllBytes(directory.resolve("${receipt.tag}.json")))) {
            "Fixture closing receipt differs from its baseline"
        }
        return true
    }

    fun closingReceipts(): List<DisposableFixtureReceipt> {
        if (!Files.exists(directory)) return emptyList()
        return Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".closing") }.map { marker ->
                val tag = marker.fileName.toString().removeSuffix(".closing")
                val receipt = requireNotNull(read(tag)) { "Closing fixture has no owner receipt" }
                check(isClosing(receipt))
                receipt
            }.toList()
        }
    }

    fun allReceipts(): List<DisposableFixtureReceipt> {
        if (!Files.exists(directory)) return emptyList()
        return Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".json") }
                .map { requireNotNull(read(it.fileName.toString().removeSuffix(".json"))) }.toList()
        }
    }

    private fun writeNew(target: Path, bytes: ByteArray) {
        Files.createDirectories(directory)
        check(!Files.exists(target)) { "Fixture receipt already exists" }
        val temporary = Files.createTempFile(directory, ".fixture-", ".tmp")
        try {
            Files.write(temporary, bytes)
            java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temporary, target, ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
