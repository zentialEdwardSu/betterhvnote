package com.betterhv.note.export

internal data class ExportActionAvailability(
    val saveEnabled: Boolean,
    val sendEnabled: Boolean,
    val deleteEnabled: Boolean
) {
    companion object {
        fun resolve(
            state: ExportTaskState,
            busy: Boolean,
            phoneTransferAvailable: Boolean
        ): ExportActionAvailability {
            val destinationEnabled = !busy && state != ExportTaskState.SOURCE_MISSING
            return ExportActionAvailability(
                saveEnabled = destinationEnabled,
                sendEnabled = destinationEnabled && phoneTransferAvailable,
                deleteEnabled = !busy
            )
        }
    }
}
