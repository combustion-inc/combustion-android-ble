/*
 * Project: Combustion Inc. Android Framework
 * File: EngineStatusFlags.kt
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

package inc.combustion.framework.service

import inc.combustion.framework.isBitSet

data class EngineStatusFlags(
    val appMode: Boolean = false,
    val controlDeviceConnected: Boolean = false,
    val lidOpen: Boolean = false,
    val fixedSpeed: Boolean = false,
) {

    companion object {
        fun fromRawByte(byte: UByte): EngineStatusFlags {
            return EngineStatusFlags(
                appMode = byte.isBitSet(0),
                controlDeviceConnected = byte.isBitSet(1),
                lidOpen = byte.isBitSet(2),
                fixedSpeed = byte.isBitSet(3),
            )
        }
    }
}