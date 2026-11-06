package com.sneakyutils.util

import java.util.Locale

/**
 * Server identity from the `P_SERVER_NAME` environment variable.
 * Values are read on each call; both getters return null rather than inventing an identity.
 */
object ServerUtil {
    private const val SERVER_NAME_ENV = "P_SERVER_NAME"
    private val separatorRun = Regex("[^\\p{L}\\p{N}]+")

    /** The supplied server name, exactly as set (for display); null when missing or blank. */
    @JvmStatic
    fun getServerName(): String? = serverNameOf(System.getenv(SERVER_NAME_ENV))

    /**
     * Lowercase kebab-case id derived from [getServerName], e.g. `Dev Main` -> `dev-main`.
     * Null when the name is missing/blank or contains no letters or digits.
     */
    @JvmStatic
    fun getServerId(): String? = serverIdOf(getServerName())

    internal fun serverNameOf(raw: String?): String? = raw?.takeUnless { it.isBlank() }

    internal fun serverIdOf(name: String?): String? =
        name?.lowercase(Locale.ROOT)?.replace(separatorRun, "-")?.trim('-')?.ifEmpty { null }
}
