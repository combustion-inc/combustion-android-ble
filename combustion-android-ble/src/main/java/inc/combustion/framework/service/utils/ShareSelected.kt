/*
 * Project: Combustion Inc. Android Framework
 * File: ShareSelected.kt
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

package inc.combustion.framework.service.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * The flow [select] picks from this flow's current value, following it as the value changes, or
 * null while there is no value. Shared eagerly in [scope], so its value stays current even while
 * nothing collects it.
 *
 * With [scope] on `Dispatchers.Unconfined`, a value set on the selected flow is passed on in the
 * thread that set it, so it's already this flow's value once the setter returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <S : Any, T> StateFlow<S?>.shareSelected(
    scope: CoroutineScope,
    select: (S) -> StateFlow<T?>,
): StateFlow<T?> =
    flatMapLatest { source -> source?.let(select) ?: flowOf(null) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = value?.let(select)?.value,
        )
