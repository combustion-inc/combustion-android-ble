/*
 * Project: Combustion Inc. Android Framework
 * File: NodeLinkingWire.kt
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

package inc.combustion.framework.ble.uart.meatnet

import inc.combustion.framework.ble.getLittleEndianUInt32At
import inc.combustion.framework.ble.putLittleEndianUInt32At
import inc.combustion.framework.service.CombustionProductType
import inc.combustion.framework.service.NodeLinkingSerialNumber
import inc.combustion.framework.service.NodeLinkingTableEntry
import java.util.Date

/**
 * Byte formats shared by the MeatNet linking messages (0x73-0x7B); see the "Read Linked Devices"
 * through "Sync Link Table" sections and "Product Serial Number" of the MeatNet Node BLE
 * specification.
 */
internal object NodeLinkingWire {
    const val PRODUCT_SERIAL_LENGTH = 12
    const val NODE_SERIAL_LENGTH = 10
    const val LEGACY_MAC_ADDRESS_LENGTH = 6

    // product type (1) + product serial number (12) + timestamp (4)
    const val DEVICE_PAYLOAD_LENGTH = 17

    // page (1) + total pages (1) + total count (1) + count on page (1)
    const val PAGE_HEADER_LENGTH = 4

    // device payload (17) + legacy flag (1)
    const val ENTRY_LENGTH = 18
    const val MAX_ENTRIES_PER_PAGE = 4

    private const val PADDING: UByte = 0xFFu

    data class DevicePayload(
        val productType: CombustionProductType,
        val serialNumber: NodeLinkingSerialNumber,
        val timestamp: UInt,
    )

    data class Page(
        val page: Int,
        val totalPages: Int,
        val totalCount: Int,
        val entries: List<NodeLinkingTableEntry>,
    )

    /**
     * Encodes [serialNumber] as a 12-byte product serial number: a little-endian UInt32 in bytes
     * 0-3 for a probe, a 10-byte string in bytes 0-9 otherwise, with the remaining bytes set to
     * 0xFF. Returns null if [serialNumber] isn't valid for [productType]: a probe serial number must
     * be hex that fits in a UInt32, and any other product's must be exactly 10 bytes in UTF-8 and
     * not of type [CombustionProductType.UNKNOWN].
     */
    fun encodeProductSerialNumber(
        productType: CombustionProductType,
        serialNumber: String,
    ): UByteArray? {
        val result = UByteArray(PRODUCT_SERIAL_LENGTH) { PADDING }
        when (productType) {
            CombustionProductType.UNKNOWN -> return null
            CombustionProductType.PROBE -> {
                val value = serialNumber.toLongOrNull(radix = 16)
                    ?.takeIf { it in 0..UInt.MAX_VALUE.toLong() }
                    ?: return null
                result.putLittleEndianUInt32At(0, value.toUInt())
            }

            else -> {
                val bytes = serialNumber.encodeToByteArray().toUByteArray()
                if (bytes.size != NODE_SERIAL_LENGTH) return null
                bytes.copyInto(result)
            }
        }
        return result
    }

    /**
     * Encodes the 17-byte payload of Link Device (0x75) and Unlink Device (0x76), or null if
     * [serialNumber] isn't valid for [productType] (see [encodeProductSerialNumber]).
     */
    fun encodeDevicePayload(
        productType: CombustionProductType,
        serialNumber: String,
        timestamp: UInt,
    ): UByteArray? {
        val serialData = encodeProductSerialNumber(productType, serialNumber) ?: return null
        val payload = UByteArray(DEVICE_PAYLOAD_LENGTH)
        payload[0] = productType.type
        serialData.copyInto(payload, destinationOffset = 1)
        payload.putLittleEndianUInt32At(1 + PRODUCT_SERIAL_LENGTH, timestamp)
        return payload
    }

    /**
     * Decodes the 12-byte product serial number in [data] starting at [start].
     */
    fun decodeSerialNumber(
        productType: CombustionProductType,
        data: UByteArray,
        start: Int,
        isLegacy: Boolean,
    ): NodeLinkingSerialNumber = when {
        isLegacy -> NodeLinkingSerialNumber.LegacyMacAddress(
            data.slice(start until start + LEGACY_MAC_ADDRESS_LENGTH).joinToString(":") {
                it.toString(16).padStart(2, '0').uppercase()
            }
        )

        productType == CombustionProductType.PROBE -> NodeLinkingSerialNumber.Probe(
            Integer.toHexString(data.getLittleEndianUInt32At(start).toInt()).uppercase()
        )

        else -> NodeLinkingSerialNumber.Node(
            String(
                data.sliceArray(start until start + NODE_SERIAL_LENGTH).toByteArray(),
                Charsets.UTF_8,
            ).trim('\u0000')
        )
    }

    /**
     * Parses a 17-byte device payload in [data] starting at [start], or null if [payloadLength]
     * isn't [DEVICE_PAYLOAD_LENGTH] or [data] is too short.
     */
    fun parseDevicePayload(data: UByteArray, start: Int, payloadLength: Int): DevicePayload? {
        if (payloadLength != DEVICE_PAYLOAD_LENGTH || data.size < start + DEVICE_PAYLOAD_LENGTH) {
            return null
        }
        val productType = CombustionProductType.fromUByte(data[start])
        return DevicePayload(
            productType = productType,
            serialNumber = decodeSerialNumber(productType, data, start + 1, isLegacy = false),
            timestamp = data.getLittleEndianUInt32At(start + 1 + PRODUCT_SERIAL_LENGTH),
        )
    }

    /**
     * Parses one page of a Read Linked Devices (0x73) or Read Unlinked Devices (0x74) response in
     * [data] starting at [start], or null if it's malformed: too short for its entries, more than
     * [MAX_ENTRIES_PER_PAGE] entries or more than its total count, a legacy flag other than 0 or 1,
     * or a legacy entry when ![permitsLegacyEntries] (unlinked tables never contain any).
     */
    fun parsePage(
        data: UByteArray,
        start: Int,
        payloadLength: Int,
        permitsLegacyEntries: Boolean,
    ): Page? {
        if (payloadLength < PAGE_HEADER_LENGTH || data.size < start + payloadLength) {
            return null
        }
        val page = data[start].toInt()
        val totalPages = data[start + 1].toInt()
        val totalCount = data[start + 2].toInt()
        val countOnPage = data[start + 3].toInt()

        if (countOnPage > MAX_ENTRIES_PER_PAGE ||
            countOnPage > totalCount ||
            payloadLength < PAGE_HEADER_LENGTH + countOnPage * ENTRY_LENGTH
        ) {
            return null
        }

        val entries = (0 until countOnPage).map { index ->
            val entryStart = start + PAGE_HEADER_LENGTH + index * ENTRY_LENGTH
            val productType = CombustionProductType.fromUByte(data[entryStart])
            val timestampIndex = entryStart + 1 + PRODUCT_SERIAL_LENGTH
            val timestamp = data.getLittleEndianUInt32At(timestampIndex)
            val isLegacy = when (data[timestampIndex + 4].toInt()) {
                0 -> false
                1 -> true
                else -> return null
            }
            if (isLegacy && !permitsLegacyEntries) {
                return null
            }
            NodeLinkingTableEntry(
                productType = productType,
                serialNumber = decodeSerialNumber(productType, data, entryStart + 1, isLegacy),
                timestamp = timestamp.toDate(),
                isLegacy = isLegacy,
            )
        }

        return Page(page, totalPages, totalCount, entries)
    }

    /** A Unix timestamp in seconds, 0 meaning unavailable. */
    fun Date?.toTimestamp(): UInt = this?.let { (it.time / 1000L).toUInt() } ?: 0u

    private fun UInt.toDate(): Date? = takeIf { it != 0u }?.let { Date(it.toLong() * 1000L) }
}
