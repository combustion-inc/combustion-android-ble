/*
 * Project: Combustion Inc. Android Framework
 * File: JobManager.kt
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

package inc.combustion.framework.ble

import android.util.Log
import kotlinx.coroutines.Job

/**
 * The [JobManager] contains multiple lists of [Job]s that can be cancelled. Use [addJob] with just
 * the job parameter to add a [Job] to the list of generic jobs--this can be cancelled with
 * [cancelJobs] with no parameters.
 *
 * Use [addJob] with a key and a job to add a [Job] to a specific list of jobs. This can be
 * cancelled by calling [cancelJobs] with the same key.
 *
 * Also note that [cancelJobs] with no key will cancel both the generic jobs as well as jobs added
 * with a key.
 */
class JobManager {
    private val genericJobList = mutableListOf<Job>()
    private val specificJobLists = mutableMapOf<String, List<Job>>()

    /**
     * Add [job] to the list of generic jobs.
     */
    fun addJob(job: Job) {
        genericJobList.add(job)
    }

    /**
     * Add [job] to the list of jobs with the given [key].
     */
    fun addJob(key: String?, job: Job) {
        key?.let {
            specificJobLists[it] = specificJobLists[key]?.plus(job) ?: listOf(job)
        } ?: addJob(job)
    }

    /**
     * Cancel all jobs in the generic job list as well as all jobs added with a key.
     */
    fun cancelJobs() {
        genericJobList.forEach { it.cancel() }

        specificJobLists.keys.forEach {
            cancelJobs(it)
        }
    }

    /**
     * Cancel all jobs in the list of jobs with the given [key].
     */
    fun cancelJobs(key: String) {
        specificJobLists[key]?.forEach { it.cancel() }
    }
}