/*
 * Project: Combustion Inc. Android Framework
 * File: AnalyticsReporter.kt
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

package inc.combustion.framework.analytics

import inc.combustion.framework.SingletonHolder
import inc.combustion.framework.analytics.AnalyticsEvent.EventName
import inc.combustion.framework.service.CombustionProductType
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class AnalyticsTracker {
    companion object : SingletonHolder<AnalyticsTracker>({ AnalyticsTracker() })

    private val _analyticsFlow =
        MutableSharedFlow<AnalyticsEvent>(replay = 5, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val analyticsFlow: SharedFlow<AnalyticsEvent>
        get() = _analyticsFlow.asSharedFlow()

    fun trackEvent(event: AnalyticsEvent) {
        _analyticsFlow.tryEmit(event)
    }

    fun trackStartDfu(productType: CombustionProductType?, serialNumber: String?) {
        val params = genDfuParams(productType, serialNumber)
        trackEvent(AnalyticsEvent(EventName.DFU_STARTED, params))
    }

    fun trackRetryDfu(productType: CombustionProductType?, serialNumber: String?) {
        val params = genDfuParams(productType, serialNumber)
        trackEvent(AnalyticsEvent(EventName.DFU_RETRY, params))
    }

    private fun genDfuParams(
        productType: CombustionProductType?,
        serialNumber: String?,
    ): Map<String, Any> =
        mutableMapOf<String, Any>().apply {
            serialNumber?.let {
                this[AnalyticsEvent.EventParams.PARAM_SERIAL] = it
            }
            productType?.let {
                this[AnalyticsEvent.EventParams.PARAM_PRODUCT_TYPE] = it
            }
        }.toMap()
}