package com.maplesu.core.theme

/** External VIEW/SEND and the document picker share the same bounded package reader. */
internal object ThemeImportPolicy {
    const val SUKISU_MIME = "application/x-sukisu-theme"

    fun accepts(displayName: String, mimeType: String?): Boolean =
        displayName.endsWith(".fpt", ignoreCase = true) ||
            displayName.endsWith(".sukisu", ignoreCase = true) ||
            mimeType?.substringBefore(';')?.trim()?.equals(SUKISU_MIME, ignoreCase = true) == true
}
