package com.sneakyutils.util;

import java.util.Set;
import java.util.HashSet;
import java.util.Collections;

public class Region<T> {

	private T value;
	private final Set<BlockLocation> blocks = new HashSet<>();

	public Region(T value) {
		this.value = value;
	}

	public T getValue() {
		return value;
	}

	public void setValue(T value) {
		this.value = value;
	}

	public void addBlock(BlockLocation block) {
		blocks.add(block);
	}

	public void removeBlock(BlockLocation block) {
		blocks.remove(block);
	}

	public boolean isEmpty() {
		return blocks.isEmpty();
	}

	public Set<BlockLocation> getBlocks() {
		return Collections.unmodifiableSet(blocks);
	}

}
