/*
 * Project: Combustion Inc. Android Framework
 * File: GaugeID.kt
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
 * The 1-8 ID a gauge can be assigned via [DeviceManager.setGaugeID], distinct from [ProbeID] --
 * see [IdTag]'s KDoc -- even though the two share the same underlying encoding.
 *
 * Unlike [ProbeID]'s [fromUByte][ProbeID.fromUByte], this decodes a full, dedicated status byte
 * (see `GaugeStatus.id`) rather than bits packed alongside other fields, so no mask/shift is
 * needed -- see [fromUByte] for why it also doesn't fall back to [ID1] the way [ProbeID.fromRaw]
 * does.
 */
enum class GaugeID(override val type: UByte) : IdTag {
    ID1(0x00u),
    ID2(0x01u),
    ID3(0x02u),
    ID4(0x03u),
    ID5(0x04u),
    ID6(0x05u),
    ID7(0x06u),
    ID8(0x07u);

    companion object {
        /**
         * Returns null for a byte outside 0-7. Unlike [ProbeID.fromRaw]'s fallback to [ID1], this
         * isn't defensive: a gauge genuinely supports more IDs than this 8-entry enum models, so
         * an out-of-range byte is a real device state, not corrupt/legacy data -- silently
         * reporting it as ID1 would misrepresent the device. Callers that need to show *something*
         * for the picker's 8 options should treat null as "none selected," not coerce to ID1 --
         * see `Gauge.knownId`.
         */
        fun fromUByte(byte: UByte): GaugeID? = idTagFromType(entries, byte)

        fun stringValues(): List<String> {
            return entries.map { it.toString() }
        }
    }
}
