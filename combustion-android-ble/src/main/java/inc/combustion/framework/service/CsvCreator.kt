package inc.combustion.framework.service

import android.app.Application

class CsvCreator(
    private val app: Application
) {
    companion object {
        private lateinit var INSTANCE: CsvCreator

        val instance: CsvCreator get() = INSTANCE
    }

    /*
    fun writeCsvFile(name: String, header: String, content: List<LoggedProbeDataPoint>) {
        Log.d(LOG_TAG, "Creating CSV file " + name)

        File(app.noBackupFilesDir, name).printWriter().use { out ->
            out.println(LoggedProbeDataPoint.csvHeader())
            content?.forEach {
                out.println(it.toCSV())
                Log.d(LOG_TAG, it.toCSV())
            }
        }
    }
     */
}