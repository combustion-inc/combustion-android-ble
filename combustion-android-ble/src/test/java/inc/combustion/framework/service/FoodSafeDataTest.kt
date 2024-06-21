/*
 * Project: Combustion Inc. Android Framework
 * File: FoodSafeDataTest.kt
 * Author: Nick Helseth <nick@sasq.io>
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

import junitparams.JUnitParamsRunner
import junitparams.Parameters
import junitparams.naming.TestCaseName
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(JUnitParamsRunner::class)
class FoodSafeDataTest {
    @Test
    fun `fromRaw Mode`() {
        val raw = ubyteArrayOf(0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u)
        assertTrue(FoodSafeData.fromRawData(raw) is FoodSafeData.Simplified)

        raw[0] = 0x01u
        assertTrue(FoodSafeData.fromRawData(raw) is FoodSafeData.Integrated)
    }

    @Test
    fun `fromRaw Simplified Product`() {
        val raw = ubyteArrayOf(0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u)

        val toProduct: (UByteArray) -> FoodSafeData.Simplified.Product = { value ->
            val foodSafeData = FoodSafeData.fromRawData(value)
            assertTrue(foodSafeData is FoodSafeData.Simplified)
            (FoodSafeData.fromRawData(value) as FoodSafeData.Simplified).product
        }

        var productValue = 0x00u
        assertEquals(FoodSafeData.Simplified.Product.Default, toProduct(raw))
        productValue += 1u
        raw[0] = (0x00u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Simplified.Product.AnyPoultry, toProduct(raw))
        productValue += 1u
        raw[0] = (0x00u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Simplified.Product.BeefCuts, toProduct(raw))
        productValue += 1u
        raw[0] = (0x00u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Simplified.Product.PorkCuts, toProduct(raw))
        productValue += 1u
        raw[0] = (0x00u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Simplified.Product.VealCuts, toProduct(raw))
        productValue += 1u
        raw[0] = (0x00u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Simplified.Product.LambCuts, toProduct(raw))
        productValue += 1u
        raw[0] = (0x00u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Simplified.Product.GroundMeats, toProduct(raw))
        productValue += 1u
        raw[0] = (0x00u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Simplified.Product.HamFreshOrSmoked, toProduct(raw))
        productValue += 1u
        raw[0] = (0x00u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Simplified.Product.HamCookedAndReheated, toProduct(raw))
        productValue += 1u
        raw[0] = (0x00u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Simplified.Product.Eggs, toProduct(raw))
        productValue += 1u
        raw[0] = (0x00u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Simplified.Product.FishAndShellfish, toProduct(raw))
        productValue += 1u
        raw[0] = (0x00u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Simplified.Product.Leftovers, toProduct(raw))
        productValue += 1u
        raw[0] = (0x00u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Simplified.Product.Casseroles, toProduct(raw))

        productValue = 62u
        raw[0] = (0x00u or (productValue shl 3)).toUByte()
        assertNull(FoodSafeData.fromRawData(raw))
    }

    @Test
    fun `fromRaw Integrated Product`() {
        val raw = ubyteArrayOf(0x01u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u)

        val toProduct: (UByteArray) -> FoodSafeData.Integrated.Product = { value ->
            val foodSafeData = FoodSafeData.fromRawData(value)
            assertTrue(foodSafeData is FoodSafeData.Integrated)
            (FoodSafeData.fromRawData(value) as FoodSafeData.Integrated).product
        }

        var productValue = 0x00u
        assertEquals(FoodSafeData.Integrated.Product.Default, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.Meats, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.MeatsGround, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.DeprecatedChicken, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.PoultryGround, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.DepracatedPork, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.DeprecatedPorkGround, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.DeprecatedHam, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.DeprecatedHamGround, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.DeprecatedTurkey, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.DeprecatedTurkeyGround, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.DeprecatedLamb, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.DeprecatedLambGround, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.Seafood, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.SeafoodGround, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.DairyMilkLessThan10PercentFat, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.Other, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.SeafoodStuffed, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.Eggs, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.EggsYolk, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.EggsWhite, toProduct(raw))
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(
            FoodSafeData.Integrated.Product.DairyCreamsGreaterThan10PercentFat,
            toProduct(raw)
        )
        productValue += 1u
        raw[0] = (0x01u or (productValue shl 3)).toUByte()
        assertEquals(FoodSafeData.Integrated.Product.DairyOther, toProduct(raw))
        raw[0] = 0xF9u
        raw[1] = 0x1Fu
        assertEquals(FoodSafeData.Integrated.Product.Custom, toProduct(raw))

        raw[0] = 0xF8u
        assertNull(FoodSafeData.fromRawData(raw))
    }

    @Test
    fun `fromRaw Serving`() {
        val toServing: (UByteArray) -> FoodSafeData.Serving = { value ->
            val foodSafeData = FoodSafeData.fromRawData(value)
            assertTrue(foodSafeData is FoodSafeData.Simplified)
            (FoodSafeData.fromRawData(value) as FoodSafeData.Simplified).serving
        }

        val raw = ubyteArrayOf(
            0x00u,
            0b0000_0000u,
            0x00u,
            0x00u,
            0x00u,
            0x00u,
            0x00u,
            0x00u,
            0x00u,
            0x00u,
        )
        assertEquals(FoodSafeData.Serving.Immediately, toServing(raw))

        raw[1] = 0b0010_0000u
        assertEquals(FoodSafeData.Serving.CookAndChill, toServing(raw))

        raw[1] = 0b0100_0000u
        assertNull(FoodSafeData.fromRawData(raw))
    }

    @Test
    fun `fromRaw Selected Threshold`() {
        val raw = ubyteArrayOf(0x01u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u)

        val toSelectedThreshold: (UByteArray) -> Double = { value ->
            val foodSafeData = FoodSafeData.fromRawData(value)
            assertTrue(foodSafeData is FoodSafeData.Integrated)
            (FoodSafeData.fromRawData(value) as FoodSafeData.Integrated).completionCriteria.selectedThresholdReferenceTemperature
        }

        // 0x1555
        raw[2] = 0b0101_0101u
        raw[3] = 0b1111_0101u
        assertEquals(273.05, toSelectedThreshold(raw), 0.1)

        // 0xAAA
        raw[2] = 0b1010_1010u
        raw[3] = 0b1110_1010u
        assertEquals(136.5, toSelectedThreshold(raw), 0.1)
    }

    @Test
    fun `fromRaw Z Value`() {
        val raw = ubyteArrayOf(0x01u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u)

        val toZValue: (UByteArray) -> Double = { value ->
            val foodSafeData = FoodSafeData.fromRawData(value)
            assertTrue(foodSafeData is FoodSafeData.Integrated)
            (FoodSafeData.fromRawData(value) as FoodSafeData.Integrated).completionCriteria.zValue
        }

        raw[3] = 0b1010_1111u
        raw[4] = 0b1010_1010u
        raw[5] = 0b1111_1110u
        assertEquals(273.05, toZValue(raw), 0.1)

        raw[3] = 0b0101_0111u
        raw[4] = 0b0101_0101u
        raw[5] = 0b1111_1101u
        assertEquals(136.5, toZValue(raw), 0.1)
    }

    @Test
    fun `fromRaw Reference Temperature`() {
        val raw = ubyteArrayOf(0x01u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u)

        val toReferenceTemperature: (UByteArray) -> Double = { value ->
            val foodSafeData = FoodSafeData.fromRawData(value)
            assertTrue(foodSafeData is FoodSafeData.Integrated)
            (FoodSafeData.fromRawData(value) as FoodSafeData.Integrated).completionCriteria.referenceTemperature
        }

        raw[5] = 0b0101_0111u
        raw[6] = 0b1101_0101u
        assertEquals(273.05, toReferenceTemperature(raw), 0.1)

        raw[5] = 0b1010_1011u
        raw[6] = 0b1010_1010u
        assertEquals(136.5, toReferenceTemperature(raw), 0.1)
    }

    @Test
    fun `fromRaw D Value at RT`() {
        val raw = ubyteArrayOf(0x01u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u)

        val toDValueAtRt: (UByteArray) -> Double = { value ->
            val foodSafeData = FoodSafeData.fromRawData(value)
            assertTrue(foodSafeData is FoodSafeData.Integrated)
            (FoodSafeData.fromRawData(value) as FoodSafeData.Integrated).completionCriteria.dValueAtRt
        }

        raw[6] = 0b1111_1111u
        raw[7] = 0b1010_1010u
        raw[8] = 0b1111_1010u
        assertEquals(273.05, toDValueAtRt(raw), 0.1)

        raw[6] = 0b0111_1111u
        raw[7] = 0b0101_0101u
        raw[8] = 0b1111_0101u
        assertEquals(136.5, toDValueAtRt(raw), 0.1)
    }

    @Test
    fun `fromRaw Target Log Reduction`() {
        val raw = ubyteArrayOf(0x01u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u)

        val toTargetLogReduction: (UByteArray) -> Double = { value ->
            val foodSafeData = FoodSafeData.fromRawData(value)
            assertTrue(foodSafeData is FoodSafeData.Integrated)
            (FoodSafeData.fromRawData(value) as FoodSafeData.Integrated).completionCriteria.targetLogReduction
        }

        raw[8] = 0b0101_1111u
        raw[9] = 0b1111_0101u
        assertEquals(8.5, toTargetLogReduction(raw), 0.1)

        raw[8] = 0b1010_1111u
        raw[9] = 0b1111_1010u
        assertEquals(17.0, toTargetLogReduction(raw), 0.1)
    }

    @Test
    fun `fromRaw Invalid Values Return null`() {
        val raw = ubyteArrayOf(0xFFu, 0xFFu, 0xFFu, 0xFFu, 0xFFu, 0xFFu, 0xFFu, 0xFFu, 0xFFu, 0xFFu)

        assertNull(FoodSafeData.fromRawData(raw))
    }

    @Test
    fun `toRaw and fromRaw translate (simplified)`() {
        val foodSafeData = FoodSafeData.Simplified(
            product = FoodSafeData.Simplified.Product.HamFreshOrSmoked,
            serving = FoodSafeData.Serving.CookAndChill,
        )

        val rawFoodSafeData = foodSafeData.toRaw
        val convertedFoodSafeData = FoodSafeData.fromRawData(rawFoodSafeData)

        assertEquals(foodSafeData, convertedFoodSafeData)
    }

    @Test
    @Parameters(method = "toAndFromRawTranslateIntegratedParams")
    @TestCaseName("toRaw and fromRaw translate (Integrated) {0}")
    fun `toRaw and fromRaw translate (integrated)`(
        givenProduct: FoodSafeData.Integrated.Product,
    ) {
        val foodSafeData = FoodSafeData.Integrated(
            product = givenProduct,
            serving = FoodSafeData.Serving.CookAndChill,
            completionCriteria = FoodSafeData.Integrated.CompletionCriteria(
                selectedThresholdReferenceTemperature = 273.05,
                zValue = 136.5,
                referenceTemperature = 273.05,
                dValueAtRt = 229.7,
                targetLogReduction = 17.0,
            )
        )

        val rawFoodSafeData = foodSafeData.toRaw
        val convertedFoodSafeData = FoodSafeData.fromRawData(rawFoodSafeData)

        assertEquals(foodSafeData, convertedFoodSafeData)
    }

    private fun toAndFromRawTranslateIntegratedParams(): Array<FoodSafeData.Integrated.Product> =
        FoodSafeData.Integrated.Product.values()
}