package com.sneakyutils.util

import com.sneakyutils.SneakyUtils
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.entity.Entity
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.EntitiesLoadEvent

class AlteredBlockListener : Listener {

    // Records in the chunk's PDC are there as soon as the chunk loads: restore the ones a crash left.
    // Legacy markers (written before chunk records) may already be present with the chunk; on modern
    // Paper they usually are not, so EntitiesLoadEvent below is their primary path. Both are safe to
    // run for one marker: restoreFromMarker removes the entity, and an invalid entity is ignored.
    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        SneakyUtils.getAlteredBlockManager().restoreChunk(event.chunk)
        restoreOrphans(event.chunk.entities.asList())
    }

    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        restoreOrphans(event.entities)
    }

    private fun restoreOrphans(entities: Collection<Entity>) {
        val manager = SneakyUtils.getAlteredBlockManager()
        for (entity in ArrayList(entities)) {
            if (!entity.isValid) continue
            if (!entity.scoreboardTags.contains(SneakyUtils.ALTERED_BLOCK_TAG)) continue
            val block = manager.getMarkedBlock(entity)
            if (block != null && manager.isTracked(block)) continue
            manager.restoreFromMarker(entity)
        }
    }
}
