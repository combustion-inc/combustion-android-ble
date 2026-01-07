/*
 * Project: Combustion Inc. Android Framework
 * File: SnapshotMap.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2026. Combustion Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package inc.combustion.framework.service.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

/**
 * Concurrent map with cooperative strong snapshot support.
 *
 * Normal operations are lock-free and rely on ConcurrentHashMap.
 * Snapshot operations take a write lock to briefly block writers
 * that also cooperate via this class -- e.g. taking a [snapshot]
 * while [removeIf] is in progress will not return a half-applied removal.
 *
 * Invariant:
 *  - All mutations must go through this class.
 *  - Snapshot consistency relies on cooperative locking.
 */
@Suppress("unused")
class ConcurrentSnapshotMap<K : Any, V : Any>(
    initial: Map<K, V> = emptyMap(),
) {

    private val map = ConcurrentHashMap<K, V>(initial)

    /**
     * Used ONLY to coordinate snapshot consistency.
     */
    private val snapshotLock = ReentrantReadWriteLock()

    /* ---------------------------
     * Basic Map Operations
     * --------------------------- */

    operator fun get(key: K): V? = map[key]

    fun put(key: K, value: V): V? = map.put(key, value)

    operator fun set(key: K, value: V) {
        put(key, value)
    }

    fun remove(key: K): V? = map.remove(key)

    fun clear() {
        map.clear()
    }

    fun putAll(from: Map<out K, V>) {
        map.putAll(from)
    }

    operator fun plus(other: Map<K, V>): Map<K, V> {
        val map = snapshot()
        return HashMap(map).apply {
            putAll(other)
        }
    }

    operator fun plus(other: ConcurrentSnapshotMap<K, V>): Map<K, V> {
        // Take snapshots independently to avoid lock ordering issues
        val leftSnapshot = this.snapshot()
        val rightSnapshot = other.snapshot()

        return HashMap(leftSnapshot).apply {
            putAll(rightSnapshot)
        }
    }

    fun filter(
        predicate: (Map.Entry<K, V>) -> Boolean
    ): Map<K, V> {
        val snapshot = snapshot()
        return snapshot.filter(predicate)
    }

    /* ---------------------------
     * Atomic / CHM-native ops
     * --------------------------- */

    fun removeIf(predicate: (Map.Entry<K, V>) -> Boolean): Boolean =
        snapshotLock.write {
            var removed = false
            val iterator = map.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (predicate(entry)) {
                    iterator.remove()
                    removed = true
                }
            }
            removed
        }

    fun putIfAbsent(key: K, value: V): V? = map.putIfAbsent(key, value)

    fun computeIfAbsent(key: K, mapping: (K) -> V): V = map.computeIfAbsent(key, mapping)

    fun compute(
        key: K,
        remapping: (K, V?) -> V?,
    ): V? = map.compute(key, remapping)

    fun remove(key: K, value: V): Boolean = map.remove(key, value)

    /* ---------------------------
     * Query Operations
     * --------------------------- */

    fun containsKey(key: K): Boolean = map.containsKey(key)

    fun containsValue(value: V): Boolean = map.containsValue(value)

    fun isEmpty(): Boolean = map.isEmpty()

    fun size(): Int = map.size

    /* ---------------------------
     * Snapshot Operations
     * --------------------------- */

    /**
     * Returns a STRONGLY CONSISTENT snapshot.
     * Blocks cooperative writers briefly.
     */
    fun snapshot(): Map<K, V> =
        snapshotLock.write {
            HashMap(map)
        }

    /**
     * Snapshot entries for iteration safety.
     */
    fun snapshotEntries(): Set<Map.Entry<K, V>> = snapshot().entries

    fun snapshotKeys(): Set<K> = snapshot().keys

    fun snapshotValues(): Collection<V> = snapshot().values

    /* ---------------------------
     * Iteration (weakly consistent)
     * --------------------------- */

    /**
     * Weakly consistent iteration.
     * Fast, but NOT snapshot-safe.
     */
    fun forEach(action: (K, V) -> Unit) {
        map.forEach(action)
    }

    /**
     * Weakly consistent iteration.
     * Fast, but NOT snapshot-safe.
     */
    fun forEach(action: (Map.Entry<K, V>) -> Unit) {
        map.forEach(action)
    }

    /* ---------------------------
     * Debug / Diagnostics
     * --------------------------- */

    override fun toString(): String {
        val snap = snapshot()
        return "ConcurrentSnapshotMap(size=${snap.size}, snapshot=$snap)"
    }
}

operator fun <K : Any, V, R : Any> ConcurrentSnapshotMap<K, V>.plus(
    other: ConcurrentSnapshotMap<K, out R>
): Map<K, R> where V : R {
    val leftSnapshot: Map<K, R> = this.snapshot()
    val rightSnapshot: Map<K, R> = other.snapshot()

    return HashMap<K, R>(leftSnapshot).apply {
        putAll(rightSnapshot)
    }
}
