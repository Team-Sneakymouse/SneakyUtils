package com.sneakyutils.util

import com.sneakyutils.SneakyUtils
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.entity.Entity
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.EntitiesLoadEvent

class AlteredBlockListener : Listener {

    // Legacy path: entities may already be present with the chunk. On modern Paper they usually
    // are not, so EntitiesLoadEvent below is the primary path. Both are safe to run for one marker:
    // restoreFromMarker removes the entity, and an invalid entity is ignored on a second pass.
    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
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
