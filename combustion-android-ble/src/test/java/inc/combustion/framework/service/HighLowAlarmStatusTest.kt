/*
 * Project: Combustion Inc. Android Framework
 * File: HighLowAlarmStatusTest.kt
 * Author:
 *
 * MIT License
 *
 * Copyright (c) 2025. Combustion Inc.
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

import inc.combustion.framework.service.HighLowAlarmStatus.AlarmStatus
import junitparams.JUnitParamsRunner
import junitparams.Parameters
import junitparams.naming.TestCaseName
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(JUnitParamsRunner::class)
class HighLowAlarmStatusTest {

    @Test
    @Parameters(method = "alarmRawDataParams")
    @TestCaseName("given alarm {0} when convert to raw data and covert convert back then expect same value")
    fun `given alarm status when convert to raw data and convert back to alarm status then expect same value`(
        givenAlarmStatus: HighLowAlarmStatus
    ) {
        assertEquals(
            HighLowAlarmStatus.fromRawData(givenAlarmStatus.toRawData()),
            givenAlarmStatus,
        )
    }

    fun alarmRawDataParams() = arrayOf(
        arrayOf(HighLowAlarmStatus.DEFAULT),
        arrayOf(
            HighLowAlarmStatus(
                AlarmStatus(
                    set = true,
                    tripped = true,
                    alarming = true,
                    Temperature(100.0),
                ),
                AlarmStatus(
                    set = true,
                    tripped = true,
                    alarming = true,
                    Temperature(100.0),
                ),
            )
        ),
    )
}