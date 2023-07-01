/*
 * Project: Combustion Inc. Android Framework
 * File: ModelInformation.kt
 * Author: https://github.com/angrygorilla
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

data class ModelInformation(
    val productType: CombustionProductType,
    val sku: String,
    val manufacturingLot: String
) {

    companion object {
        /**
         * Converts [modelInformationString], which is the contents of the DIS Model String
         * characteristic into a [ModelInformation] object.
         *
         * The model information string is different depending on the device type:
         * - Probes are of the form "<SKU>:<manufacturing lot>".
         * - Repeater Nodes are of the form "<Device Type> <SKU>-<manufacturing lot>" where 'Device
         *   Type' is the plain-text name of the device ("Timer", "Charger", etc.).
         */
        fun fromString(modelInformationString: String?): ModelInformation {
            modelInformationString?.let {
                // If the string has a colon in it, it's a probe.
                if (it.contains(":")) {
                    val split = it.split(":")

                    return ModelInformation(
                        productType = CombustionProductType.PROBE,
                        sku = split[0],
                        manufacturingLot = split[1]
                    )
                } else {
                    val split = it.split(" ")

                    if (split.size == 2) {
                        val skuLot = split[1].split("-")

                        if (skuLot.size == 2) {
                            return ModelInformation(
                                productType = CombustionProductType.fromModelString(split[0]),
                                sku = skuLot[0],
                                manufacturingLot = skuLot[1]
                            )
                        }
                    }
                }
            }

            return ModelInformation(
                productType = CombustionProductType.UNKNOWN,
                sku = "",
                manufacturingLot = ""
            )
        }
    }
}
