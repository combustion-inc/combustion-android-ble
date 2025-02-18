/*
 * Project: Combustion Inc. Android Framework
 * File: StateFlowMap.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2024. Combustion Inc.
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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A StateFlow with an interface similar to a mutable map.
 * Note, does not implement the [MutableMap] interface, e.g. cant modify its entries and keys directly.
 */
@Suppress("unused")
class StateFlowMutableMap<K, V>(initialMap: Map<K, V> = emptyMap()) {
    private val mutableMap: MutableMap<K, V> = initialMap.toMutableMap()
    private val _stateFlow = MutableStateFlow(initialMap)
    val stateFlow: StateFlow<Map<K, V>> get() = _stateFlow

    private fun emitChange() {
        _stateFlow.value = mutableMap.toMap() // Emit a new copy
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun put(key: K, value: V): V? {
        val prevValue = mutableMap.put(key, value)
        emitChange()
        return prevValue
    }

    fun remove(key: K): V? {
        val removedValue = mutableMap.remove(key)
        emitChange()
        return removedValue
    }

    operator fun set(key: K, value: V) {
        put(key, value)
    }

    operator fun get(key: K): V? = mutableMap[key]

    val entries: Set<Map.Entry<K, V>>
        get() = mutableMap.entries.toSet()

    val keys: Set<K>
        get() = mutableMap.keys.toSet()

    val size: Int
        get() = mutableMap.size

    val values: Set<V>
        get() = mutableMap.values.toSet()

    fun clear() {
        mutableMap.clear()
        emitChange()
    }

    fun isEmpty(): Boolean = mutableMap.isEmpty()

    fun containsValue(value: V): Boolean = mutableMap.containsValue(value)

    fun containsKey(key: K): Boolean = mutableMap.containsKey(key)

    fun putAll(from: Map<out K, V>) {
        mutableMap.putAll(from)
        emitChange()
    }

    fun removeIf(filter: (Map.Entry<K, V>) -> Boolean): Boolean {
        var removed = false
        mutableMap.toMap().forEach { entry ->
            if (filter(entry)) {
                mutableMap.remove(entry.key)
                removed = true
            }
        }
        if (removed) {
            emitChange()
        }
        return removed
    }

    fun toMap(): Map<K, V> = this.mutableMap.toMap()

    fun asStateFlow(): StateFlow<Map<K, V>> = stateFlow
}