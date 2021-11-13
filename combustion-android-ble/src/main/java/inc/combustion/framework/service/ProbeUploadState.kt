package inc.combustion.framework.service

/**
 * Enumerates upload states of probe data
 */
sealed class ProbeUploadState {

    /**
     * Upload is not available (e.g. not connected)
     */
    object Unavailable : ProbeUploadState()

    /**
     * Upload is needed (e.g. device has records to be transferred)
     */
    object ProbeUploadNeeded : ProbeUploadState()

    /**
     * Upload is in progress.
     *
     * @property recordsTransferred Number of records transferred statistic
     * @property logResponseDropCount Total number of dropped record transfer logs statistic
     * @property recordsRequested Number of records requested for transfer statistic
     */
    data class ProbeUploadInProgress(
        val recordsTransferred: UInt,
        val logResponseDropCount: UInt,
        val recordsRequested: UInt
    ) : ProbeUploadState()

    /**
     * Upload has been completed.  Data is being synchronized in real-time.
     *
     * @property sessionMinSequence Min sequence number on phone for current session.
     * @property sessionMaxSequence Max sequence number on phone for current session.
     * @property totalRecords Total number of records on the phone for current session.
     * @property logResponseDropCount Total number of dropped record transfer logs statistic
     * @property deviceStatusDropCount Total number of dropped device status records statistic
     */
    data class ProbeUploadComplete(
        val sessionMinSequence: UInt,
        val sessionMaxSequence: UInt,
        val totalRecords: UInt,
        val logResponseDropCount: UInt,
        val deviceStatusDropCount: UInt
    ) : ProbeUploadState()
}