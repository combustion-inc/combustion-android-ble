/*
 * Project: Combustion Inc. Android Framework
 * File: ModelInformationTest.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2023. Combustion Inc.
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

import inc.combustion.framework.service.dfu.DfuProductType
import org.junit.Test
import kotlin.test.assertEquals

internal class ModelInformationTest {
    @Test
    fun `Check Probe Model Information String`() {
        val mi = ModelInformation.fromString("10010101:20220914")

        assertEquals(CombustionProductType.PROBE, mi.productType)
        assertEquals(DfuProductType.PROBE, mi.dfuProductType)
        assertEquals("10010101", mi.sku)
        assertEquals("20220914", mi.manufacturingLot)
    }

    @Test
    fun `Check Timer Model Information String`() {
        val mi = ModelInformation.fromString("Timer 123-ABC")

        assertEquals(CombustionProductType.NODE, mi.productType)
        assertEquals(DfuProductType.DISPLAY, mi.dfuProductType)
        assertEquals("123", mi.sku)
        assertEquals("ABC", mi.manufacturingLot)
    }

    @Test
    fun `Check Charger Model Information String`() {
        val mi = ModelInformation.fromString("Charger 123-ABC")

        assertEquals(CombustionProductType.NODE, mi.productType)
        assertEquals(DfuProductType.CHARGER, mi.dfuProductType)
        assertEquals("123", mi.sku)
        assertEquals("ABC", mi.manufacturingLot)
    }

    @Test
    fun `Check Malformed Strings Fail`() {
        assertEquals(
            CombustionProductType.UNKNOWN,
            ModelInformation.fromString("1001010120220914").productType
        )
        assertEquals(
            DfuProductType.UNKNOWN,
            ModelInformation.fromString("1001010120220914").dfuProductType
        )
        assertEquals(
            CombustionProductType.UNKNOWN,
            ModelInformation.fromString("10010101 20220914").productType
        )
        assertEquals(
            DfuProductType.UNKNOWN,
            ModelInformation.fromString("10010101 20220914").dfuProductType
        )
        assertEquals(
            CombustionProductType.UNKNOWN,
            ModelInformation.fromString("Timer 20220914").productType
        )
        assertEquals(
            DfuProductType.UNKNOWN,
            ModelInformation.fromString("Timer 20220914").dfuProductType
        )
        assertEquals(
            CombustionProductType.UNKNOWN,
            ModelInformation.fromString("Charger123-ABC").productType
        )
        assertEquals(
            DfuProductType.UNKNOWN,
            ModelInformation.fromString("Charger123-ABC").dfuProductType
        )
    }
}
