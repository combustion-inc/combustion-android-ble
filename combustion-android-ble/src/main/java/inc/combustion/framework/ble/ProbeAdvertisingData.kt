/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeAdvertisingData.kt
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

package inc.combustion.framework.ble

import inc.combustion.framework.service.CombustionProductType

/**
 * Advertising data specific to a Combustion probe.
 *
 * @note This data may be sourced from either a probe directly or rebroadcast from a MeatNet node.
 *
 * @param isDirectConnection If true, this advertising data was obtained directly from a probe; if
 *                           false, the data was rebroadcast over MeatNet.
 */
internal class ProbeAdvertisingData(
    mac: String,
    name: String,
    rssi: Int,
    productType: CombustionProductType,
    isConnectable: Boolean,

    val isDirectConnection: Boolean,
    // TODO: Additional probe advertising data (temperatures, etc.)
): AdvertisingData(mac, name, rssi, productType, isConnectable) {
}
