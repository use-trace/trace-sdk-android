package io.usetrace.sdk

import android.content.Context
import java.util.UUID

/**
 * The install scoped anonymous key this SDK sends as `anon_user_key`.
 *
 * It is a random [UUID] with the hyphens removed, behind the prefix the server uses for its own anonymous keys,
 * and `app_` to say where it came from. It is derived from nothing about the device: no advertising id, no
 * hardware id, no fingerprint, so two installs on the same phone are two different people as far as Trace is
 * concerned, which is the correct answer.
 *
 * It lives in its own SharedPreferences file, `io.usetrace.sdk.installid`, and that file is excluded from Android
 * backup and from device transfer. An install id must not survive an uninstall: a restored id on a resold or
 * factory reset device would join a previous owner's installs to a new person. A reinstall therefore counts as a
 * new install, and over counting reinstalls is the better failure.
 *
 * **What a host app has to do.** The exclusions ship in this library's manifest, as
 * `android:fullBackupContent` and `android:dataExtractionRules`, and they only reach the built app by manifest
 * merge. An app that sets either attribute itself wins: the merge either fails with a conflict, which the usual
 * fix of `tools:replace` then resolves in the app's favour, or the app's own rules file is simply the one that is
 * used. Either way this library's file is dropped and nothing warns anybody at runtime. If your app has its own
 * backup rules, copy these two lines into them:
 *
 * ```xml
 * <!-- in your full-backup-content file -->
 * <exclude domain="sharedpref" path="io.usetrace.sdk.installid.xml" />
 * <!-- in your data-extraction-rules file, inside BOTH cloud-backup and device-transfer -->
 * <exclude domain="sharedpref" path="io.usetrace.sdk.installid.xml" />
 * ```
 *
 * The id is a visitor identity, so it is never logged, and this object never logs.
 */
public object InstallId {

    private const val PREFS_FILE: String = "io.usetrace.sdk.installid"
    private const val PREFS_KEY: String = "install_id"

    /**
     * Returns this install's id, minting and persisting one on the first call.
     *
     * Call it from any thread: minting is guarded and the write is synchronous, so a crash straight after the
     * first call cannot lose the id and mint a second one. It does no network work and never logs the value.
     */
    @JvmStatic
    public fun get(context: Context): String = synchronized(this) {
        val prefs = prefs(context)
        prefs.getString(PREFS_KEY, null) ?: mint().also {
            // commit, not apply: an id that was handed out but not written would be minted again as a second
            // install.
            prefs.edit().putString(PREFS_KEY, it).commit()
        }
    }

    /**
     * Returns this install's id, or null when there is not one yet. Reading does not create one.
     *
     * This is what a host app shows on its own privacy screen, so that a person can find the identifier Trace
     * holds for them. Before the first [get] it answers null, truthfully, rather than minting an id in order to
     * display it.
     */
    @JvmStatic
    public fun peek(context: Context): String? = prefs(context).getString(PREFS_KEY, null)

    // The application context keeps the preferences off whatever short lived context was handed in. It is null
    // when the SDK is called from Application.attachBaseContext or a ContentProvider that runs before the
    // application object exists, so fall back to the given context rather than crash the host app.
    private fun prefs(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun mint(): String = "auk_app_" + UUID.randomUUID().toString().replace("-", "")
}
