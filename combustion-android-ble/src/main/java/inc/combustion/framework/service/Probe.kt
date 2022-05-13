/*
 * Project: Combustion Inc. Android Framework
 * File: Probe.kt
 * Author: https://github.com/miwright2
 *
 * MIT License
 *
 * Copyright (c) 2022. Combustion Inc.
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

/**
 * Data class for the current state of a probe.
 *
 * @property serialNumber Serial Number
 * @property mac Bluetooth MAC Address
 * @property fwVersion Firmware Version
 * @property hwRevision Hardware Revision
 * @property temperatures Current temperature values
 * @property rssi Received signal strength
 * @property minSequence Minimum log sequence number
 * @property maxSequence Current sequence number
 * @property connectionState Connection state
 * @property uploadState Upload State
 *
 * @see DeviceConnectionState
 * @see ProbeUploadState
 * @see ProbeTemperatures
 */
data class Probe(
    val serialNumber: String,
    val mac: String,
    val fwVersion: String?,
    val hwRevision: String?,
    val temperatures: ProbeTemperatures,
    val rssi: Int,
    val minSequence: UInt,
    val maxSequence: UInt,
    val connectionState: DeviceConnectionState,
    val uploadState: ProbeUploadState
)