/*
 * Project: Combustion Inc. Android Framework
 * File: IdTag.kt
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
package inc.combustion.framework.service

/**
 * Common shape of [ProbeID] and [GaugeID]: the 1-8 slot identifier a device is assigned within
 * its product type, used internally to encode the SET_PROBE_ID/SET_GAUGE_ID UART payloads.
 * [ProbeID] and [GaugeID] stay distinct public types -- despite sharing this encoding -- so the
 * app-facing API for one product type can never be handed the other's ID by mistake.
 */
interface IdTag {
    val type: UByte
}

/**
 * Looks up the entry (if any) whose [IdTag.type] equals [byte] in [entries]. Shared by
 * [ProbeID.fromRaw] and [GaugeID.fromUByte] (called with `entries` -- the compiler-generated,
 * cached `EnumEntries` -- not `enumValues<T>()`/`values()`, which allocate a fresh array on every
 * call) so the actual "byte -> enum entry" lookup is written once; each type still owns its own
 * byte-extraction (e.g. [ProbeID.fromUByte]'s mask/shift) and, more importantly, its own fallback
 * for a byte that matches no entry -- see those functions' KDocs for why that fallback differs
 * between the two.
 */
fun <T : IdTag> idTagFromType(entries: List<T>, byte: UByte): T? =
    entries.firstOrNull { it.type == byte }
