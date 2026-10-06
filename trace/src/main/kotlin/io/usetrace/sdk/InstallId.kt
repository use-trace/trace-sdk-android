package io.usetrace.sdk

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * The install scoped anonymous key this SDK sends as `anon_user_key`.
 *
 * It is a random [UUID] with the hyphens removed, behind the prefix the server uses for its own anonymous keys,
 * and `app_` to say where it came from. It is derived from nothing about the device: no advertising id, no
 * hardware id, no fingerprint, so two installs on the same phone are two different people as far as Trace is
 * concerned, which is the correct answer.
 *
 * **It does not survive an uninstall, and a host app cannot change that.** The id lives in a single file in
 * `Context.getNoBackupFilesDir()`. Android excludes that directory from Auto Backup, from a device transfer and
 * from a cross platform transfer, always: its documentation says files there "are always excluded even if you try
 * to include them", and that a backup mode left out of an app's data extraction rules is "fully enabled for all
 * content except for no-backup and cache directories". So the exclusion holds whatever the host app's
 * `android:fullBackupContent` or `android:dataExtractionRules` say, and an integrator has nothing to carry across.
 *
 * It is excluded for two reasons. An install id identifies one install, so an id that came back after a reinstall
 * would count the reinstall as the same install and the install numbers would be wrong. And it is a visitor
 * identity: restoring it would quietly join a person back to the journey history they had before they uninstalled,
 * which someone who removed the app may reasonably treat as finished. A reinstall therefore mints a fresh id and
 * counts as a new install.
 *
 * The id is a visitor identity, so it is never logged, and this object never logs.
 *
 * It is internal on purpose. When an id is minted is the SDK's decision, made alongside the consent gate, and a host
 * app that could call [get] would mint one outside it. A host app reads the id through [Trace.installId], which
 * never mints one. The `api` check fails if this object becomes public again.
 */
internal object InstallId {

    private const val FILE_NAME: String = "install_id"

    /**
     * Returns this install's id, minting and persisting one on the first call.
     *
     * Call it from any thread: minting is guarded. It does no network work and never logs the value. If the write
     * fails it still returns an id, because an event with an id the SDK could not keep is better than a crash in
     * someone else's app; the next launch mints a fresh one.
     */
    @JvmStatic
    internal fun get(context: Context): String = synchronized(this) {
        val file = file(context)
        read(file) ?: mint().also { runCatching { file.writeText(it) } }
    }

    /**
     * Returns this install's id, or null when there is not one yet. Reading does not create one.
     *
     * This is what [Trace.installId] reads, so that a host app can show a person the identifier Trace holds for
     * them on its own privacy screen. Before the first [get] it answers null, truthfully, rather than minting an id
     * in order to display it.
     */
    @JvmStatic
    internal fun peek(context: Context): String? = read(file(context))

    // The application context keeps the file off whatever short lived context was handed in. It is null when the
    // SDK is called from Application.attachBaseContext or a ContentProvider that runs before the application
    // object exists, so fall back to the given context rather than crash the host app.
    private fun file(context: Context): File =
        File((context.applicationContext ?: context).noBackupFilesDir, FILE_NAME)

    // A half written or unreadable file is treated as no id, so the next get mints one.
    private fun read(file: File): String? =
        runCatching { file.readText().trim().ifEmpty { null } }.getOrNull()

    private fun mint(): String = "auk_app_" + UUID.randomUUID().toString().replace("-", "")
}
