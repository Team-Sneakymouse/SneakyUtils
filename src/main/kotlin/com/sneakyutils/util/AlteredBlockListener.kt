package com.sneakyutils.util

import com.sneakyutils.SneakyUtils
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent

class AlteredBlockListener : Listener {

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        val manager = SneakyUtils.getAlteredBlockManager()

        for (entity in event.chunk.entities) {
            if (!entity.scoreboardTags.contains(SneakyUtils.ALTERED_BLOCK_TAG)) continue
            val block = manager.getMarkedBlock(entity)
            if (block != null && manager.isTracked(block)) continue
            manager.restoreFromMarker(entity)
        }
    }
}
