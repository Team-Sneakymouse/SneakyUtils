package com.sneakyutils.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.sneakyutils.SneakyUtils
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.DyeColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Banner
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Container
import org.bukkit.block.CreatureSpawner
import org.bukkit.block.Lectern
import org.bukkit.block.Sign
import org.bukkit.block.banner.Pattern
import org.bukkit.block.banner.PatternType
import org.bukkit.block.data.BlockData
import org.bukkit.block.sign.Side
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Marker
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.Base64
import java.util.UUID
import java.util.logging.Logger

class AlteredBlockManager(plugin: JavaPlugin) {

    private val alteredBlocks: MutableMap<Block, AlteredBlock> = HashMap()
    private val logger: Logger = plugin.logger
    private val blockDataKey = NamespacedKey(plugin, "altered_block_data")
    private val blockStateKey = NamespacedKey(plugin, "altered_block_state")
    private val worldKey = NamespacedKey(plugin, "altered_block_world")
    private val xKey = NamespacedKey(plugin, "altered_block_x")
    private val yKey = NamespacedKey(plugin, "altered_block_y")
    private val zKey = NamespacedKey(plugin, "altered_block_z")

    fun add(change: Change) {
        val isNew = !alteredBlocks.containsKey(change.block)
        val alteredBlock = alteredBlocks.computeIfAbsent(change.block) { AlteredBlock(change) }
        alteredBlock.changes.add(change)
        if (isNew) {
            alteredBlock.marker = spawnMarker(change.block, change.fromData, change.fromState)
        }
    }

    /**
     * Captures the current block, applies [newData], and registers a reversible [Change].
     */
    fun apply(internalName: String, block: Block, newData: BlockData, applyPhysics: Boolean): Change {
        val fromData = block.blockData
        val fromState = block.state
        block.setBlockData(newData, applyPhysics)
        val change = Change(internalName, block, fromData, fromState)
        add(change)
        return change
    }

    /**
     * Captures the current block, applies [material]'s default BlockData, and registers a reversible [Change].
     */
    fun apply(internalName: String, block: Block, material: Material, applyPhysics: Boolean): Change =
        apply(internalName, block, material.createBlockData(), applyPhysics)

    /**
     * Registers a reversible [Change] for a block that has already been modified
     * (e.g. by WorldEdit). Does not write to the world.
     */
    fun register(internalName: String, block: Block, fromData: BlockData, fromState: BlockState): Change {
        val change = Change(internalName, block, fromData, fromState)
        add(change)
        return change
    }

    fun isTracked(block: Block): Boolean = alteredBlocks.containsKey(block)

    fun getByInternalName(internalName: String): List<Change> {
        val result = ArrayList<Change>()
        for (alteredBlock in alteredBlocks.values) {
            for (change in alteredBlock.changes) {
                if (change.internalName == internalName) {
                    result.add(change)
                }
            }
        }
        return result
    }

    fun getByBlock(block: Block): List<Change> {
        val alteredBlock = alteredBlocks[block] ?: return emptyList()
        return ArrayList(alteredBlock.changes)
    }

    fun getByBlockAndInternalName(block: Block, internalName: String): List<Change> {
        val alteredBlock = alteredBlocks[block] ?: return emptyList()
        val result = ArrayList<Change>()
        for (change in alteredBlock.changes) {
            if (change.internalName == internalName) {
                result.add(change)
            }
        }
        return result
    }

    /**
     * Undoes every change registered under [internalName] (LIFO per block).
     */
    fun undoAll(internalName: String, applyPhysics: Boolean) {
        val changes = getByInternalName(internalName)
        for (i in changes.indices.reversed()) {
            changes[i].undo(applyPhysics)
        }
    }

    /**
     * Resolves the block this marker was created for (from PDC), falling back to the entity's current block.
     */
    fun getMarkedBlock(entity: Entity?): Block? {
        if (entity == null) return null
        val pdc = entity.persistentDataContainer
        val worldId = pdc.get(worldKey, PersistentDataType.STRING)
        val x = pdc.get(xKey, PersistentDataType.INTEGER)
        val y = pdc.get(yKey, PersistentDataType.INTEGER)
        val z = pdc.get(zKey, PersistentDataType.INTEGER)
        if (worldId != null && x != null && y != null && z != null) {
            try {
                val world = Bukkit.getWorld(UUID.fromString(worldId))
                if (world != null) return world.getBlockAt(x, y, z)
                logger.severe("Altered-block marker references missing world $worldId")
                return null
            } catch (_: IllegalArgumentException) {
                logger.severe("Altered-block marker has invalid world UUID: $worldId")
                return null
            }
        }
        return entity.location.block
    }

    /**
     * Restores a block from an orphan marker (prior session) and removes the entity.
     */
    fun restoreFromMarker(entity: Entity?) {
        if (entity == null || !entity.isValid) return
        if (!entity.scoreboardTags.contains(SneakyUtils.ALTERED_BLOCK_TAG)) return

        val block = getMarkedBlock(entity)
        val pdc = entity.persistentDataContainer
        val dataString = pdc.get(blockDataKey, PersistentDataType.STRING)
        if (block != null && dataString != null) {
            try {
                block.setBlockData(Bukkit.createBlockData(dataString), false)
            } catch (_: IllegalArgumentException) {
                logger.severe("Invalid altered-block marker BlockData at ${block.location}: $dataString")
            }

            val stateString = pdc.get(blockStateKey, PersistentDataType.STRING)
            if (stateString != null) {
                try {
                    applySerializedTileData(block, stateString, false)
                } catch (e: RuntimeException) {
                    logger.severe("Invalid altered-block marker tile data at ${block.location}: ${e.message}")
                }
            }
        }
        entity.remove()
    }

    private fun spawnMarker(block: Block, originalData: BlockData, originalState: BlockState): Marker {
        val loc: Location = block.location.add(0.5, 0.5, 0.5)
        val marker = block.world.spawnEntity(loc, EntityType.MARKER) as Marker
        marker.isPersistent = true
        marker.setGravity(false)
        marker.isSilent = true
        marker.isInvulnerable = true
        marker.addScoreboardTag(SneakyUtils.ALTERED_BLOCK_TAG)
        val pdc = marker.persistentDataContainer
        pdc.set(blockDataKey, PersistentDataType.STRING, originalData.asString)
        val tileData = serializeTileData(originalState)
        if (tileData != null) {
            pdc.set(blockStateKey, PersistentDataType.STRING, tileData)
        }
        pdc.set(worldKey, PersistentDataType.STRING, block.world.uid.toString())
        pdc.set(xKey, PersistentDataType.INTEGER, block.x)
        pdc.set(yKey, PersistentDataType.INTEGER, block.y)
        pdc.set(zKey, PersistentDataType.INTEGER, block.z)
        return marker
    }

    private fun removeMarker(alteredBlock: AlteredBlock) {
        val marker = alteredBlock.marker
        if (marker != null && marker.isValid) {
            marker.remove()
        }
        alteredBlock.marker = null
    }

    class AlteredBlock(change: Change) {
        val originalBlockData: BlockData = change.fromData
        val originalBlockState: BlockState = change.fromState
        val changes: MutableList<Change> = ArrayList()
        var marker: Entity? = null
    }

    /**
     * Java-compatible record-style accessors (`internalName()`, `block()`, …) via [JvmRecord].
     */
    @JvmRecord
    data class Change(
        val internalName: String,
        val block: Block,
        val fromData: BlockData,
        val fromState: BlockState,
    ) {
        fun undo(applyPhysics: Boolean) {
            val manager = SneakyUtils.getAlteredBlockManager()
            val alteredBlock = manager.alteredBlocks[block]
            if (alteredBlock != null && alteredBlock.changes.contains(this)) {
                if (alteredBlock.changes.size == 1) {
                    restoreBlockState(alteredBlock.originalBlockData, alteredBlock.originalBlockState, applyPhysics)
                    manager.removeMarker(alteredBlock)
                    manager.alteredBlocks.remove(block)
                } else {
                    restoreBlockState(fromData, fromState, applyPhysics)
                    alteredBlock.changes.remove(this)
                }
            }
        }

        private fun restoreBlockState(blockData: BlockData, blockState: BlockState, applyPhysics: Boolean) {
            block.setBlockData(blockData, applyPhysics)
            val tileData = serializeTileData(blockState)
            if (tileData != null) {
                applySerializedTileData(block, tileData, applyPhysics)
            }
        }
    }

    companion object {
        private val COMPONENT_JSON = GsonComponentSerializer.gson()

        /**
         * Serializes tile-entity state for cross-session marker restore (signs, containers, etc.).
         * Returns null when there is nothing beyond BlockData to store.
         */
        @JvmStatic
        fun serializeTileData(state: BlockState?): String? {
            if (state == null) return null

            val root = JsonObject()
            root.addProperty("v", 1)

            when (state) {
                is Sign -> {
                    root.addProperty("kind", "sign")
                    root.add("front", serializeSignSide(state.getSide(Side.FRONT)))
                    root.add("back", serializeSignSide(state.getSide(Side.BACK)))
                    root.addProperty("waxed", state.isWaxed)
                }
                is Container -> {
                    root.addProperty("kind", "container")
                    root.addProperty("items", encodeItems(state.inventory.contents))
                }
                is Lectern -> {
                    root.addProperty("kind", "lectern")
                    root.addProperty("items", encodeItems(state.inventory.contents))
                    root.addProperty("page", state.page)
                }
                is CreatureSpawner -> {
                    root.addProperty("kind", "spawner")
                    val spawned = state.spawnedType
                    if (spawned != null) {
                        root.addProperty("spawnedType", spawned.name)
                    }
                    root.addProperty("delay", state.delay)
                    root.addProperty("minSpawnDelay", state.minSpawnDelay)
                    root.addProperty("maxSpawnDelay", state.maxSpawnDelay)
                    root.addProperty("spawnCount", state.spawnCount)
                    root.addProperty("maxNearbyEntities", state.maxNearbyEntities)
                    root.addProperty("requiredPlayerRange", state.requiredPlayerRange)
                    root.addProperty("spawnRange", state.spawnRange)
                }
                is Banner -> {
                    root.addProperty("kind", "banner")
                    val patterns = JsonArray()
                    for (pattern in state.patterns) {
                        val entry = JsonObject()
                        entry.addProperty("color", pattern.color.name)
                        val key = RegistryAccess.registryAccess()
                            .getRegistry(RegistryKey.BANNER_PATTERN).getKey(pattern.pattern)
                        entry.addProperty("pattern", key?.toString() ?: pattern.pattern.toString())
                        patterns.add(entry)
                    }
                    root.add("patterns", patterns)
                }
                else -> return null
            }

            return root.toString()
        }

        private fun serializeSignSide(side: org.bukkit.block.sign.SignSide): JsonObject {
            val obj = JsonObject()
            val lines = JsonArray()
            for (i in 0 until 4) {
                lines.add(COMPONENT_JSON.serialize(side.line(i)))
            }
            obj.add("lines", lines)
            obj.addProperty("glowing", side.isGlowingText)
            val color = side.color
            if (color != null) {
                obj.addProperty("color", color.name)
            }
            return obj
        }

        private fun encodeItems(contents: Array<ItemStack?>): String =
            Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(contents))

        @Suppress("UNCHECKED_CAST")
        private fun decodeItems(encoded: String): Array<ItemStack?> =
            ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(encoded)) as Array<ItemStack?>

        @JvmStatic
        fun applySerializedTileData(block: Block, json: String, applyPhysics: Boolean) {
            val root: JsonObject
            try {
                root = JsonParser.parseString(json).asJsonObject
            } catch (e: JsonSyntaxException) {
                throw IllegalArgumentException("malformed tile JSON", e)
            } catch (e: IllegalStateException) {
                throw IllegalArgumentException("malformed tile JSON", e)
            }

            val kind = if (root.has("kind")) root.get("kind").asString else ""
            val state = block.state

            when (kind) {
                "sign" -> {
                    if (state !is Sign) return
                    applySignSide(state.getSide(Side.FRONT), root.getAsJsonObject("front"))
                    applySignSide(state.getSide(Side.BACK), root.getAsJsonObject("back"))
                    if (root.has("waxed")) {
                        state.isWaxed = root.get("waxed").asBoolean
                    }
                    state.update(true, applyPhysics)
                }
                "container" -> {
                    if (state !is Container) return
                    state.inventory.contents = decodeItems(root.get("items").asString)
                    state.update(true, applyPhysics)
                }
                "lectern" -> {
                    if (state !is Lectern) return
                    state.inventory.contents = decodeItems(root.get("items").asString)
                    if (root.has("page")) {
                        state.page = root.get("page").asInt
                    }
                    state.update(true, applyPhysics)
                }
                "spawner" -> {
                    if (state !is CreatureSpawner) return
                    if (root.has("spawnedType")) {
                        try {
                            state.spawnedType = EntityType.valueOf(root.get("spawnedType").asString)
                        } catch (_: IllegalArgumentException) {
                        }
                    }
                    if (root.has("delay")) state.delay = root.get("delay").asInt
                    if (root.has("minSpawnDelay")) state.minSpawnDelay = root.get("minSpawnDelay").asInt
                    if (root.has("maxSpawnDelay")) state.maxSpawnDelay = root.get("maxSpawnDelay").asInt
                    if (root.has("spawnCount")) state.spawnCount = root.get("spawnCount").asInt
                    if (root.has("maxNearbyEntities")) state.maxNearbyEntities = root.get("maxNearbyEntities").asInt
                    if (root.has("requiredPlayerRange")) state.requiredPlayerRange = root.get("requiredPlayerRange").asInt
                    if (root.has("spawnRange")) state.spawnRange = root.get("spawnRange").asInt
                    state.update(true, applyPhysics)
                }
                "banner" -> {
                    if (state !is Banner) return
                    val patterns = ArrayList<Pattern>()
                    val array = root.getAsJsonArray("patterns")
                    if (array != null) {
                        for (element in array) {
                            val entry = element.asJsonObject
                            val color = DyeColor.valueOf(entry.get("color").asString)
                            val patternId = entry.get("pattern").asString
                            val type = resolvePatternType(patternId)
                            if (type != null) {
                                patterns.add(Pattern(color, type))
                            }
                        }
                    }
                    state.patterns = patterns
                    state.update(true, applyPhysics)
                }
            }
        }

        private fun applySignSide(side: org.bukkit.block.sign.SignSide, obj: JsonObject?) {
            if (obj == null) return
            val lines = obj.getAsJsonArray("lines")
            if (lines != null) {
                for (i in 0 until minOf(4, lines.size())) {
                    val line = COMPONENT_JSON.deserialize(lines.get(i).asString)
                    side.line(i, line)
                }
            }
            if (obj.has("glowing")) {
                side.isGlowingText = obj.get("glowing").asBoolean
            }
            if (obj.has("color")) {
                try {
                    side.color = DyeColor.valueOf(obj.get("color").asString)
                } catch (_: IllegalArgumentException) {
                }
            }
        }

        private fun resolvePatternType(id: String?): PatternType? {
            if (id.isNullOrBlank()) return null
            val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN)
            val trimmed = id.trim()
            val key = NamespacedKey.fromString(
                if (trimmed.contains(":")) trimmed else "minecraft:${trimmed.lowercase()}"
            )
            if (key != null) {
                val typed = registry.get(key)
                if (typed != null) return typed
            }
            return registry.get(NamespacedKey.minecraft(trimmed.lowercase()))
        }
    }
}
