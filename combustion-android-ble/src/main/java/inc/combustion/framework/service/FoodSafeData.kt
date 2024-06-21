/*
 * Project: Combustion Inc. Android Framework
 * File: FoodSafeData.kt
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

import inc.combustion.framework.ble.equalsDelta
import inc.combustion.framework.ble.shl
import inc.combustion.framework.ble.shr
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.random.nextUInt

sealed class FoodSafeData {
    abstract val mode: Mode
    abstract val serving: Serving

    enum class Mode {
        Simplified,
        Integrated,
        ;

        internal val toRaw: UByte
            get() {
                return when (this) {
                    Simplified -> 0u
                    Integrated -> 1u
                }
            }

        override fun toString(): String {
            return this.name
        }

        companion object {
            internal fun fromRaw(raw: UInt): Mode {
                return when (raw) {
                    0u -> Simplified
                    1u -> Integrated
                    else -> throw IllegalArgumentException("Invalid mode value")
                }
            }
        }
    }

    enum class Serving {
        Immediately,
        CookAndChill,
        ;

        override fun toString(): String {
            return when (this) {
                CookAndChill -> "Cook and Chill"
                else -> this.name
            }
        }

        internal val toRaw: UByte
            get() {
                return when (this) {
                    Immediately -> 0u
                    CookAndChill -> 1u
                }
            }

        companion object {
            internal fun fromRaw(raw: UInt): Serving {
                return when (raw) {
                    0u -> Immediately
                    1u -> CookAndChill
                    else -> throw IllegalArgumentException("Invalid serving value")
                }
            }
        }
    }

    data class Simplified(
        val product: Product,
        override val serving: Serving,
    ) : FoodSafeData() {
        override val mode = Mode.Simplified

        enum class Product {
            Default,
            AnyPoultry,
            BeefCuts,
            PorkCuts,
            VealCuts,
            LambCuts,
            GroundMeats,
            HamFreshOrSmoked,
            HamCookedAndReheated,
            Eggs,
            FishAndShellfish,
            Leftovers,
            Casseroles,
            ;

            override fun toString(): String {
                return when (this) {
                    Default -> "Poultry (Default)"
                    AnyPoultry -> "Any Poultry"
                    BeefCuts -> "Beef Cuts"
                    PorkCuts -> "Pork Cuts"
                    VealCuts -> "Veal Cuts"
                    LambCuts -> "Lamb Cuts"
                    GroundMeats -> "Ground Meats"
                    HamFreshOrSmoked -> "Ham, Fresh or Smoked"
                    HamCookedAndReheated -> "Ham, Cooked and Reheated"
                    FishAndShellfish -> "Fish and Shellfish"
                    else -> this.name
                }
            }

            internal val toRaw: UShort
                get() {
                    return when (this) {
                        Default -> 0u
                        AnyPoultry -> 1u
                        BeefCuts -> 2u
                        PorkCuts -> 3u
                        VealCuts -> 4u
                        LambCuts -> 5u
                        GroundMeats -> 6u
                        HamFreshOrSmoked -> 7u
                        HamCookedAndReheated -> 8u
                        Eggs -> 9u
                        FishAndShellfish -> 10u
                        Leftovers -> 11u
                        Casseroles -> 12u
                    }
                }

            companion object {
                internal fun fromRaw(raw: UInt): Product {
                    return when (raw) {
                        0u -> Default
                        1u -> AnyPoultry
                        2u -> BeefCuts
                        3u -> PorkCuts
                        4u -> VealCuts
                        5u -> LambCuts
                        6u -> GroundMeats
                        7u -> HamFreshOrSmoked
                        8u -> HamCookedAndReheated
                        9u -> Eggs
                        10u -> FishAndShellfish
                        11u -> Leftovers
                        12u -> Casseroles
                        else -> throw IllegalArgumentException("Invalid simplified product value $raw")
                    }
                }
            }
        }
    }

    data class Integrated(
        val product: Product,
        override val serving: Serving,
        val completionCriteria: CompletionCriteria,
    ) : FoodSafeData() {
        override val mode = Mode.Integrated

        enum class Product {
            Default,
            Beef,
            BeefGround,
            Chicken,
            ChickenGround,
            Pork,
            PorkGround,
            Ham,
            HamGround,
            Turkey,
            TurkeyGround,
            Lamb,
            LambGround,
            FishAndShellfish,
            FishAndShellfishGround,
            DairyMilkLessThan10PercentFat,
            Game,
            Custom,
            ;

            override fun toString(): String {
                return when (this) {
                    Default -> "Poultry (Default)"
                    BeefGround -> "Beef (Ground)"
                    ChickenGround -> "Chicken (Ground)"
                    PorkGround -> "Pork (Ground)"
                    HamGround -> "Ham (Ground)"
                    TurkeyGround -> "Turkey (Ground)"
                    LambGround -> "Lamb (Ground)"
                    FishAndShellfish -> "Fish and Shellfish"
                    FishAndShellfishGround -> "Fish and Shellfish (Ground)"
                    DairyMilkLessThan10PercentFat -> "Dairy - Milk (<10% fat)"
                    else -> this.name
                }
            }

            internal val toRaw: UShort
                get() {
                    return when (this) {
                        Default -> 0u
                        Beef -> 1u
                        BeefGround -> 2u
                        Chicken -> 3u
                        ChickenGround -> 4u
                        Pork -> 5u
                        PorkGround -> 6u
                        Ham -> 7u
                        HamGround -> 8u
                        Turkey -> 9u
                        TurkeyGround -> 10u
                        Lamb -> 11u
                        LambGround -> 12u
                        FishAndShellfish -> 13u
                        FishAndShellfishGround -> 14u
                        DairyMilkLessThan10PercentFat -> 15u
                        Game -> 16u
                        Custom -> 1023u
                    }
                }

            companion object {
                internal fun fromRaw(raw: UInt): Product {
                    return when (raw) {
                        0u -> Default
                        1u -> Beef
                        2u -> BeefGround
                        3u -> Chicken
                        4u -> ChickenGround
                        5u -> Pork
                        6u -> PorkGround
                        7u -> Ham
                        8u -> HamGround
                        9u -> Turkey
                        10u -> TurkeyGround
                        11u -> Lamb
                        12u -> LambGround
                        13u -> FishAndShellfish
                        14u -> FishAndShellfishGround
                        15u -> DairyMilkLessThan10PercentFat
                        16u -> Game
                        1023u -> Custom
                        else -> throw IllegalArgumentException("Invalid integrated product value $raw")
                    }
                }
            }
        }

        data class CompletionCriteria(
            val selectedThresholdReferenceTemperature: Double = 0.0,
            val zValue: Double = 0.0,
            val referenceTemperature: Double = 0.0,
            val dValueAtRt: Double = 0.0,
            val targetLogReduction: Double = 0.0,
        ) {
            override fun equals(other: Any?): Boolean {
                if (other !is CompletionCriteria) {
                    return false
                }

                return selectedThresholdReferenceTemperature.equalsDelta(other.selectedThresholdReferenceTemperature) &&
                        zValue.equalsDelta(other.zValue) &&
                        referenceTemperature.equalsDelta(other.referenceTemperature) &&
                        dValueAtRt.equalsDelta(other.dValueAtRt) &&
                        targetLogReduction.equalsDelta(other.targetLogReduction)
            }
        }
    }

    internal val toRaw: UByteArray
        get() {
            val data = UByteArray(SIZE_BYTES)

            val rawMode = when (this) {
                is Simplified -> Mode.Simplified
                is Integrated -> Mode.Integrated
            }.toRaw

            val rawProduct = when (this) {
                is Simplified -> this.product.toRaw
                is Integrated -> this.product.toRaw
            }

            data[0] =
                rawMode or
                        (rawProduct shl 3).toUByte()

            data[1] =
                (rawProduct shr 5).toUByte() or
                        (serving.toRaw.toUShort() shl 5).toUByte()

            val toPacked: (Double) -> UInt = { value ->
                (value / 0.05).roundToInt().toUInt() and 0x1FFFu
            }

            val rawSelectedThreshold = toPacked(
                when (this) {
                    is Simplified -> 0.0
                    is Integrated -> this.completionCriteria.selectedThresholdReferenceTemperature
                }
            )
            val rawZValue = toPacked(
                when (this) {
                    is Simplified -> 0.0
                    is Integrated -> this.completionCriteria.zValue
                }
            )
            val rawReferenceTemperature = toPacked(
                when (this) {
                    is Simplified -> 0.0
                    is Integrated -> this.completionCriteria.referenceTemperature
                }
            )
            val rawDValueAtRt = toPacked(
                when (this) {
                    is Simplified -> 0.0
                    is Integrated -> this.completionCriteria.dValueAtRt
                }
            )
            val rawTargetLogReduction = when (this) {
                is Simplified -> 0u
                is Integrated -> (this.completionCriteria.targetLogReduction / 0.1).toUInt() and 0xFFu
            }

            data[2] =
                (rawSelectedThreshold and 0b0_0000_1111_1111u).toUByte()

            data[3] =
                ((rawSelectedThreshold and 0b1_1111_0000_0000u) shr 8).toUByte() or
                        ((rawZValue and 0b0_0000_0000_0111u) shl 5).toUByte()

            data[4] =
                ((rawZValue and 0b0_0111_1111_1000u) shr 3).toUByte()

            data[5] =
                ((rawZValue and 0b1_1000_0000_0000u) shr 11).toUByte() or
                        ((rawReferenceTemperature and 0b0_0000_0011_1111u) shl 2).toUByte()

            data[6] =
                ((rawReferenceTemperature and 0b1_1111_1100_0000u) shr 6).toUByte() or
                        ((rawDValueAtRt and 0b0_0000_0000_0001u) shl 7).toUByte()

            data[7] =
                ((rawDValueAtRt and 0b0_0001_1111_1110u) shr 1).toUByte()

            data[8] =
                ((rawDValueAtRt and 0b1_1110_0000_0000u) shr 9).toUByte() or
                        ((rawTargetLogReduction and 0b0000_1111u) shl 4).toUByte()

            data[9] =
                (rawTargetLogReduction and 0b1111_0000u shr 4).toUByte()

            return data
        }

    companion object {
        internal const val SIZE_BYTES = 10

        internal fun fromRawData(data: UByteArray): FoodSafeData? {
            if (data.size < SIZE_BYTES) {
                throw IllegalArgumentException("Invalid buffer")
            }

            val rawMode = data[0] and 0b0000_0111u
            val rawProduct =
                ((data[0].toUShort() and 0b1111_1000u) shr 3) or
                        ((data[1].toUShort() and 0b0001_1111u) shl 5)
            val rawServing =
                ((data[1].toUShort() and 0b1110_0000u) shr 5)

            val rawSelectedThresholdReferenceTemperature =
                data[2].toUShort() or
                        ((data[3].toUShort() and 0b0001_1111u) shl 8)
            val rawZValue =
                ((data[3].toUShort() and 0b1110_0000u) shr 5) or
                        ((data[4].toUShort() and 0b1111_1111u) shl 3) or
                        ((data[5].toUShort() and 0b0000_0011u) shl 11)
            val rawReferenceTemperature =
                ((data[5].toUShort() and 0b1111_1100u) shr 2) or
                        ((data[6].toUShort() and 0b0111_1111u) shl 6)
            val rawDValueAtRt =
                ((data[6].toUShort() and 0b1000_0000u) shr 7) or
                        ((data[7].toUShort() and 0b1111_1111u) shl 1) or
                        ((data[8].toUShort() and 0b0000_1111u) shl 9)
            val targetLogReduction =
                ((data[8].toUShort() and 0b1111_0000u) shr 4) or
                        ((data[9].toUShort() and 0b0000_1111u) shl 4)

            try {
                return when (Mode.fromRaw(rawMode.toUInt())) {
                    Mode.Simplified -> {
                        Simplified(
                            product = Simplified.Product.fromRaw(rawProduct.toUInt()),
                            serving = Serving.fromRaw(rawServing.toUInt()),
                        )
                    }

                    Mode.Integrated -> {
                        Integrated(
                            product = Integrated.Product.fromRaw(rawProduct.toUInt()),
                            serving = Serving.fromRaw(rawServing.toUInt()),
                            completionCriteria = Integrated.CompletionCriteria(
                                selectedThresholdReferenceTemperature = rawSelectedThresholdReferenceTemperature.toDouble() * 0.05,
                                zValue = rawZValue.toDouble() * 0.05,
                                referenceTemperature = rawReferenceTemperature.toDouble() * 0.05,
                                dValueAtRt = rawDValueAtRt.toDouble() * 0.05,
                                targetLogReduction = targetLogReduction.toDouble() * 0.1,
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                return null
            }
        }

        val DEFAULT = Simplified(
            product = FoodSafeData.Simplified.Product.Default,
            serving = FoodSafeData.Serving.Immediately,
        )

        /**
         * Returns a FoodSafeData class with random data.
         */
        val RANDOM = when (Mode.fromRaw(Random.nextUInt(until = 2u))) {
            Mode.Simplified -> {
                Simplified(
                    product = Simplified.Product.fromRaw(Random.nextUInt(until = 13u)),
                    serving = Serving.Immediately,
                )
            }

            Mode.Integrated -> {
                Integrated(
                    product = Integrated.Product.fromRaw(Random.nextUInt(until = 13u)),
                    serving = Serving.Immediately,
                    completionCriteria = Integrated.CompletionCriteria(
                        selectedThresholdReferenceTemperature = Random.nextDouble(until = 100.0),
                        zValue = Random.nextDouble(until = 100.0),
                        referenceTemperature = Random.nextDouble(until = 100.0),
                        dValueAtRt = Random.nextDouble(until = 100.0),
                        targetLogReduction = Random.nextDouble(until = 25.5),
                    )
                )
            }
        }
    }
}
