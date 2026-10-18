package com.sneakyutils.util

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.Container
import org.bukkit.block.Lectern
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.AnaloguePowerable
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Hangable
import org.bukkit.block.data.Levelled
import org.bukkit.block.data.MultipleFacing
import org.bukkit.block.data.Orientable
import org.bukkit.block.data.Powerable
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Stairs
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack

/**
 * Block/material helpers ported from MagicsSpells (LOS helpers take an explicit transparent set).
 */
object BlockUtils {

    @JvmStatic
    fun getNearbyBlocks(location: Location, radius: Int, height: Int): List<Block> {
        val blocks = ArrayList<Block>()
        for (x in location.blockX - radius..location.blockX + radius) {
            for (y in location.blockY - height..location.blockY + height) {
                for (z in location.blockZ - radius..location.blockZ + radius) {
                    blocks.add(location.world!!.getBlockAt(x, y, z))
                }
            }
        }
        return blocks
    }

    @JvmStatic
    fun isTransparent(transparent: Set<Material>, block: Block): Boolean =
        transparent.contains(block.type)

    @JvmStatic
    fun getTargetBlock(entity: LivingEntity, transparent: Set<Material>, range: Int): Block? =
        try {
            entity.getTargetBlock(transparent, range)
        } catch (_: IllegalStateException) {
            null
        }

    @JvmStatic
    fun getLastTwoTargetBlock(entity: LivingEntity, transparent: Set<Material>, range: Int): List<Block>? =
        try {
            entity.getLastTwoTargetBlocks(transparent, range)
        } catch (_: IllegalStateException) {
            null
        }

    @JvmStatic
    fun setTypeAndData(block: Block, material: Material, data: BlockData, physics: Boolean) {
        block.type = material
        block.setBlockData(data, physics)
    }

    @JvmStatic
    fun setBlockFromFallingBlock(block: Block, fallingBlock: FallingBlock, physics: Boolean) {
        val blockData = fallingBlock.blockData
        block.type = blockData.material
        block.setBlockData(blockData, physics)
    }

    @JvmStatic
    @JvmOverloads
    fun setGrowthLevel(block: Block, level: Int, applyPhysics: Boolean = false) {
        val age = block.blockData as Ageable
        age.age = level
        block.setBlockData(age, applyPhysics)
    }

    @JvmStatic
    fun getWaterLevel(block: Block): Int =
        (block.blockData as Levelled).level

    @JvmStatic
    fun getGrowthLevel(block: Block): Int =
        (block.blockData as Ageable).age

    @JvmStatic
    fun getMaxGrowthLevel(block: Block): Int =
        (block.blockData as Ageable).maximumAge

    @JvmStatic
    fun isPlant(b: Block): Boolean =
        b.blockData is Ageable

    @JvmStatic
    fun getWaterLevel(blockState: BlockState): Int =
        (blockState.blockData as Levelled).level

    @JvmStatic
    fun isChest(block: Block): Boolean = isChest(block.type)

    @JvmStatic
    fun isChest(m: Material): Boolean =
        when (m) {
            Material.CHEST, Material.TRAPPED_CHEST -> true
            else -> false
        }

    @JvmStatic
    fun isLiquid(m: Material): Boolean =
        when (m) {
            Material.WATER, Material.LAVA -> true
            else -> false
        }

    @JvmStatic
    fun isPathable(block: Block): Boolean = isPathable(block.type)

    @JvmStatic
    fun isAir(item: ItemStack): Boolean = isAir(item.type)

    @JvmStatic
    fun isAir(m: Material): Boolean = m.isAir

    @JvmStatic
    fun isBed(m: Material): Boolean = m.name.contains("_BED")

    @JvmStatic
    fun isDoor(m: Material): Boolean = m.name.contains("_DOOR")

    @JvmStatic
    fun isButton(m: Material): Boolean = m.name.contains("_BUTTON")

    @JvmStatic
    fun isPressurePlate(m: Material): Boolean = m.name.contains("_PRESSURE_PLATE")

    @JvmStatic
    fun isTrapdoor(m: Material): Boolean = m.name.contains("_TRAPDOOR")

    @JvmStatic
    fun isFenceGate(m: Material): Boolean = m.name.contains("_FENCE_GATE")

    @JvmStatic
    fun isShulkerBox(m: Material): Boolean = m.name.contains("SHULKER_BOX")

    @JvmStatic
    fun isWood(m: Material): Boolean = m.name.contains("_WOOD")

    @JvmStatic
    fun isLog(m: Material): Boolean = m.name.contains("_LOG")

    @JvmStatic
    fun isPathable(mat: Material): Boolean =
        when (mat) {
            Material.LIGHT, Material.SNOW -> true
            else -> mat.isBlock && !mat.isCollidable
        }

    @JvmStatic
    fun isSafeToStand(location: Location): Boolean {
        if (!isPathable(location.block)) return false
        if (!isPathable(location.add(0.0, 1.0, 0.0).block)) return false
        return !isPathable(location.subtract(0.0, 2.0, 0.0).block) ||
            !isPathable(location.subtract(0.0, 1.0, 0.0).block)
    }

    @JvmStatic
    fun activatePowerable(block: Block) {
        val data = block.blockData
        if (data is Powerable) {
            data.isPowered = true
            block.setBlockData(data, true)
        }

        val data2 = block.blockData
        if (data2 is AnaloguePowerable) {
            data2.power = data2.maximumPower
            block.setBlockData(data2, true)
        }
    }

    @JvmStatic
    fun mergeBlockData(oldBlockData: BlockData, newBlockData: BlockData, mergeBlockData: Boolean): BlockData {
        val clone = newBlockData.clone()

        if (mergeBlockData) {
            if (oldBlockData is Directional && clone is Directional) {
                if (clone.faces.contains(oldBlockData.facing)) {
                    clone.facing = oldBlockData.facing
                }
            }

            if (oldBlockData is Stairs && clone is Stairs) {
                clone.shape = oldBlockData.shape
            }

            if (oldBlockData is Slab && clone is Slab) {
                clone.type = oldBlockData.type
            }

            if (oldBlockData is Hangable && clone is Hangable) {
                clone.isHanging = oldBlockData.isHanging
            }

            if (oldBlockData is Orientable && clone is Orientable) {
                if (clone.axes.contains(oldBlockData.axis)) {
                    clone.axis = oldBlockData.axis
                }
            }

            if (oldBlockData is MultipleFacing && clone is MultipleFacing) {
                for (blockFace in oldBlockData.faces) {
                    if (clone.allowedFaces.contains(blockFace)) {
                        clone.setFace(blockFace, true)
                    }
                }
            }

            if (oldBlockData is Bisected && clone is Bisected) {
                clone.half = oldBlockData.half
            }
        }

        return clone
    }

    @JvmStatic
    fun setBlockData(
        block: Block,
        oldBlockData: BlockData,
        newBlockData: BlockData,
        mergeBlockData: Boolean,
        applyPhysics: Boolean,
    ) {
        block.setBlockData(mergeBlockData(oldBlockData, newBlockData, mergeBlockData), applyPhysics)
    }

    @JvmStatic
    fun isContainer(block: Block?): Boolean {
        if (block == null) return false
        val state = block.state
        return state is Container || state is Lectern
    }

    @JvmStatic
    fun findNearestAirRecursive(location: Location, radius: Int): Block? {
        var nearest: Block? = null
        var minDistanceSq = Double.MAX_VALUE

        val centerX = location.blockX
        val centerY = location.blockY
        val centerZ = location.blockZ

        for (x in centerX - radius..centerX + radius) {
            for (y in centerY - radius..centerY + radius) {
                for (z in centerZ - radius..centerZ + radius) {
                    val b = location.world!!.getBlockAt(x, y, z)
                    if (isAir(b.type)) {
                        val dx = x - location.x
                        val dy = y - location.y
                        val dz = z - location.z

                        val bias = if (dy > 0) 2.0 else 0.5
                        val weightedDy = dy * bias
                        val distanceSq = dx * dx + dz * dz + weightedDy * weightedDy

                        if (distanceSq < minDistanceSq) {
                            minDistanceSq = distanceSq
                            nearest = b
                        }
                    }
                }
            }
        }

        var current = nearest
        if (current != null) {
            while (true) {
                val below = current!!.getRelative(BlockFace.DOWN)
                if (isAir(below.type)) {
                    current = below
                } else {
                    break
                }
            }
        }

        return current
    }
}
