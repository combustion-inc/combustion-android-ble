/*
 * Project: Combustion Inc. Android Framework
 * File: GaugeStatusTest.kt
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

package inc.combustion.framework.ble

import inc.combustion.framework.service.HopCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GaugeStatusTest {

    // Byte layout matches GaugeStatus.fromRawData's ranges; only newRecordFlag (index 22) and
    // hopCountByte (index 23) vary per test -- everything else is zeroed (sensorPresent=false at
    // index 8 avoids needing valid temperature bytes).
    private fun buildStatusData(newRecordFlag: UByte, hopCountByte: UByte, id: UByte? = null): UByteArray {
        val size = if (id != null) 25 else 24
        val data = UByteArray(size)
        data[22] = newRecordFlag
        data[23] = hopCountByte
        if (id != null) data[24] = id
        return data
    }

    @Test
    fun `isNewRecord is true for a HOP1 relay`() {
        val status = GaugeStatus.fromRawData(buildStatusData(newRecordFlag = 1u, hopCountByte = 0x00u))
        assertEquals(true, status?.isNewRecord)
        assertEquals(HopCount.HOP1, status?.hopCount)
    }

    @Test
    fun `isNewRecord is true for a multi-hop relay -- regression for UShort overlap with hop count`() {
        // hopCountByte's top 2 bits select HOP2; previously reading newRecordFlag as a
        // little-endian UShort pulled this byte in as the high byte, so the combined value was
        // never == 1 for any hop count above HOP1, misreporting a real new-record flag as false.
        val status = GaugeStatus.fromRawData(buildStatusData(newRecordFlag = 1u, hopCountByte = 0x40u))
        assertEquals(true, status?.isNewRecord)
        assertEquals(HopCount.HOP2, status?.hopCount)
    }

    @Test
    fun `isNewRecord is false when the flag byte is not set`() {
        val status = GaugeStatus.fromRawData(buildStatusData(newRecordFlag = 0u, hopCountByte = 0xC0u))
        assertEquals(false, status?.isNewRecord)
        assertEquals(HopCount.HOP4, status?.hopCount)
    }

    @Test
    fun `fromRawData rejects a payload one byte short of RAW_SIZE`() {
        val data = buildStatusData(newRecordFlag = 1u, hopCountByte = 0u).sliceArray(0..22)
        assertNull(GaugeStatus.fromRawData(data))
    }

    @Test
    fun `id defaults to 0 when the trailing byte is absent`() {
        val status = GaugeStatus.fromRawData(buildStatusData(newRecordFlag = 0u, hopCountByte = 0u))
        assertEquals(0u.toUByte(), status?.id)
    }

    @Test
    fun `id is parsed when the trailing byte is present`() {
        val status = GaugeStatus.fromRawData(
            buildStatusData(newRecordFlag = 0u, hopCountByte = 0u, id = 5u),
        )
        assertEquals(5u.toUByte(), status?.id)
    }
}
