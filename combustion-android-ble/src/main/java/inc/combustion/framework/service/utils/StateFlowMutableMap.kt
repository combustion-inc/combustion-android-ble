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
import kotlinx.coroutines.flow.update

/**
 * A StateFlow with an interface similar to a mutable map. Is Thread-safe.
 * Note, does not implement the [MutableMap] interface, e.g. cant modify its entries and keys directly.
 */
@Suppress("unused")
class StateFlowMutableMap<K, V>(initialMap: Map<K, V> = emptyMap()) {

    private val _stateFlow = MutableStateFlow(initialMap)
    val stateFlow: StateFlow<Map<K, V>> get() = _stateFlow

    private inline fun updateMap(transform: (MutableMap<K, V>) -> Unit) {
        _stateFlow.update { current ->
            current.toMutableMap().apply(transform)
        }
    }

    fun put(key: K, value: V): V? {
        var previous: V? = null
        updateMap { map ->
            previous = map.put(key, value)
        }
        return previous
    }

    operator fun set(key: K, value: V) {
        put(key, value)
    }

    fun remove(key: K): V? {
        var removed: V? = null
        updateMap { map ->
            removed = map.remove(key)
        }
        return removed
    }

    operator fun get(key: K): V? = _stateFlow.value[key]

    val entries: Set<Map.Entry<K, V>>
        get() = _stateFlow.value.entries

    val keys: Set<K>
        get() = _stateFlow.value.keys

    val values: Collection<V>
        get() = _stateFlow.value.values

    val size: Int
        get() = _stateFlow.value.size

    fun clear() {
        _stateFlow.value = emptyMap()
    }

    fun isEmpty(): Boolean = _stateFlow.value.isEmpty()

    fun containsKey(key: K): Boolean = _stateFlow.value.containsKey(key)

    fun containsValue(value: V): Boolean = _stateFlow.value.containsValue(value)

    fun putAll(from: Map<out K, V>) {
        updateMap { map ->
            map.putAll(from)
        }
    }

    fun filter(predicate: (Map.Entry<K, V>) -> Boolean): Map<K, V> {
        return _stateFlow.value
            .entries
            .asSequence()
            .filter(predicate)
            .associate { it.key to it.value }
    }

    fun removeIf(filter: (Map.Entry<K, V>) -> Boolean): Boolean {
        var removed = false
        updateMap { map ->
            val iterator = map.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (filter(entry)) {
                    iterator.remove()
                    removed = true
                }
            }
        }
        return removed
    }

    fun toMap(): Map<K, V> = _stateFlow.value

    fun asStateFlow(): StateFlow<Map<K, V>> = stateFlow

    override fun toString(): String = _stateFlow.value.toString()
}