/*
 * Project: Combustion Inc. Android Framework
 * File: InstantReadFilter.kt
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

package inc.combustion.framework

import kotlin.math.roundToInt

typealias InstantReadTemperatures = Pair<Double, Double>?

internal class InstantReadFilter {
    private enum class Units {
        Celsius,
        Fahrenheit
    }

    private companion object {
        const val DEADBAND_RANGE_IN_CELSIUS = 0.05
    }

    private var _values: InstantReadTemperatures = null

    /**
     * [values] is the filtered instant read values, with the first element in the pair being the
     * temperature in Celsius and the second being in Fahrenheit.
     */
    val values: InstantReadTemperatures
        get() = _values

    /**
     * Adds the reading [temperatureInCelsius] to the instant read filter--upon completion of this
     * function, the updated filtered values are in [values].
     */
    fun addReading(temperatureInCelsius: Double?) {
        if (temperatureInCelsius != null) {
            _values = Pair(
                first = calculateFilteredTemperature(
                    temperatureInCelsius = temperatureInCelsius,
                    currentDisplayTemperature = _values?.first,
                    units = Units.Celsius
                ),
                second = calculateFilteredTemperature(
                    temperatureInCelsius = temperatureInCelsius,
                    currentDisplayTemperature = _values?.second,
                    units = Units.Fahrenheit
                ),
            )
        } else {
            _values = null
        }
    }

    private fun calculateFilteredTemperature(
        temperatureInCelsius: Double,
        currentDisplayTemperature: Double?,
        units: Units
    ): Double {
        val deadbandRange = when (units) {
            Units.Celsius -> DEADBAND_RANGE_IN_CELSIUS
            Units.Fahrenheit -> celsiusToFahrenheitDifference(DEADBAND_RANGE_IN_CELSIUS)
        }

        val temperature = when (units) {
            Units.Celsius -> temperatureInCelsius
            Units.Fahrenheit -> celsiusToFahrenheitAbsolute(temperatureInCelsius)
        }

        val displayTemperature = currentDisplayTemperature ?: temperature.roundToInt().toDouble()

        val upperBound = displayTemperature + 0.5 + deadbandRange
        val lowerBound = displayTemperature - 0.5 - deadbandRange

        if (temperature > upperBound || temperature < lowerBound) {
            return temperature.roundToInt().toDouble()
        }

        return displayTemperature
    }

    private fun celsiusToFahrenheitDifference(celsius: Double): Double {
        return (celsius * 9.0) / 5.0
    }

    private fun celsiusToFahrenheitAbsolute(celsius: Double): Double {
        return celsiusToFahrenheitDifference(celsius) + 32.0
    }
}