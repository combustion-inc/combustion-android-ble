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
    val sku: String,
    val manufacturingLot: String
) {

    companion object {
        /**
         * Converts this model information string into a [ModelInformation] object, which includes the
         * SKU and manufacturing lot.
         */
        fun fromString(modelInformationString: String?): ModelInformation {
            return if(modelInformationString == null) {
                ModelInformation("", "")
            } else {
                val split = modelInformationString.split(":")
                if(split.size == 2) {
                    ModelInformation(split[0], split[1])
                } else {
                    ModelInformation("", "")
                }
            }
        }
    }
}
