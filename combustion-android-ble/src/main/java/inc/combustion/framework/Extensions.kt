package inc.combustion.framework

/***
 * Conventions for LOG_TAG
 */
val Any.LOG_TAG: String
    get() {
        val tag = "App.${javaClass.simpleName}"
        return if (tag.length <= 23) tag else tag.substring(0, 23)
    }
