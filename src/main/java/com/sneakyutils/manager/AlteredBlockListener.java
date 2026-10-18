package com.sneakyutils.manager;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import com.sneakyutils.SneakyUtils;

public class AlteredBlockListener implements Listener {

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        AlteredBlockManager manager = SneakyUtils.getAlteredBlockManager();

        for (Entity entity : event.getChunk().getEntities()) {
            if (!entity.getScoreboardTags().contains(SneakyUtils.ALTERED_BLOCK_TAG)) continue;
            Block block = manager.getMarkedBlock(entity);
            if (block != null && manager.isTracked(block)) continue;
            manager.restoreFromMarker(entity);
        }
    }

}
