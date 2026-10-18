package com.sneakyutils.util

import java.util.Collections

class Region<T>(private var value: T) {

    private val blocks: MutableSet<BlockLocation> = HashSet()

    fun getValue(): T = value

    fun setValue(value: T) {
        this.value = value
    }

    fun addBlock(block: BlockLocation) {
        blocks.add(block)
    }

    fun removeBlock(block: BlockLocation) {
        blocks.remove(block)
    }

    fun isEmpty(): Boolean = blocks.isEmpty()

    fun getBlocks(): Set<BlockLocation> = Collections.unmodifiableSet(blocks)
}
