/*
 * Project: Combustion Inc
 * File: JobManager.kt
 * Author: TODO
 *
 * Copyright (c) 2023. Combustion Inc.
 */

package inc.combustion.framework.ble

import kotlinx.coroutines.Job

class JobManager {
    private val jobList = mutableListOf<Job>()

    fun addJob(job: Job) {
        jobList.add(job)
    }

    fun cancelJobs() {
        jobList.forEach { it.cancel() }
    }
}