/*
 * Project: Combustion Inc. Android Framework
 * File: ProbeBleDeviceBase.kt
 * Author: http://github.com/miwright2
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
package inc.combustion.framework.ble.device

internal abstract class ProbeBleDeviceBase : UartCapableProbe {

    // message completion handlers
    protected val sessionInfoHandler = UartBleDevice.MessageCompletionHandler()
    protected val setColorHandler = UartBleDevice.MessageCompletionHandler()
    protected val setIdHandler = UartBleDevice.MessageCompletionHandler()
    protected val setPredictionHandler = UartBleDevice.MessageCompletionHandler()
    protected val configureFoodSafeHandler = UartBleDevice.MessageCompletionHandler()
    protected val resetFoodSafeHandler = UartBleDevice.MessageCompletionHandler()
    protected val resetProbeHandler = UartBleDevice.MessageCompletionHandler()
    protected val setPowerModeHandler = UartBleDevice.MessageCompletionHandler()

    override fun toString(): String {
        return "$mac $serialNumber $linkId $connectionState"
    }
}