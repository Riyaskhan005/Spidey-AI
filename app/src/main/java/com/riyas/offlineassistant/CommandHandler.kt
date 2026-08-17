package com.riyas.SpideyAssistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.BatteryManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import java.util.Locale
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import java.text.SimpleDateFormat
import java.util.Date

private const val TAG = "CommandHandler"

sealed class CommandResult {
    data class Time(val phrase: String) : CommandResult()
    data class Flashlight(val phrase: String) : CommandResult()
    data class Battery(val phrase: String) : CommandResult()
    data class OpenApp(val ackPhrase: String, val appName: String, val launched: Boolean) : CommandResult()
    data class Weather(val ackPhrase: String,val location: String?) : CommandResult()
    data class WebSearch(val ackPhrase: String, val query: String) : CommandResult()
    data class Location(val ackPhrase: String) : CommandResult()
}

object CommandHandler {

    // ── Trigger patterns ─────────────────────────────────────────────────────
    // NOTE: these are all anchored with ^...$, so ANY extra words before/after
    // (e.g. a wake word like "Hey Spidey, set an alarm for 6") will make them
    // fail to match. Strip wake words / trim the prompt BEFORE calling handle().

    private val BATTERY_PATTERNS = listOf(
        Regex("""(?i)how much battery"""),
        Regex("""(?i)battery (?:level|percentage|status|left)"""),
        Regex("""(?i)what'?s my battery"""),
    )

    private val WEATHER_PATTERNS = listOf(
        Regex("""(?i)^weather(?: in| at| for)?\s+(.+)$"""),
        Regex("""(?i)^what'?s the weather(?: in| at| for)?\s*(.*)$"""),
        Regex("""(?i)^how'?s the weather(?: in| at| for)?\s*(.*)$"""),
        Regex("""(?i)^weather$""")
    )

    private val OPEN_APP_PATTERNS = listOf(
        Regex("""(?i)^open (.+)$"""),
        Regex("""(?i)^launch (.+)$"""),
        Regex("""(?i)^start (.+?) app$"""),
    )

    private val WEB_SEARCH_PATTERNS = listOf(
        Regex("""(?i)^search (?:for |about )?(.+)$"""),
        Regex("""(?i)^look up (.+)$"""),
        Regex("""(?i)^google (.+)$"""),
    )

    private val LOCATION_PATTERNS = listOf(
        Regex("""(?i)where am i"""),
        Regex("""(?i)what'?s my (?:current )?location"""),
        Regex("""(?i)my location"""),
        Regex("""(?i)current location"""),
    )

    private val TIME_PATTERNS = listOf(
    Regex("""(?i)^what(?:'s| is) the time\??$"""),
    Regex("""(?i)^what time is it\??$"""),
    Regex("""(?i)^current time\??$"""),
    Regex("""(?i)^time now\??$"""),
    Regex("""(?i)^what'?s time now\??$""")
    )

    private val FLASHLIGHT_ON_PATTERNS = listOf(
        Regex("""(?i)^turn (?:on )?(?:the )?flashlight$"""),
        Regex("""(?i)^switch (?:on )?(?:the )?flashlight$"""),
        Regex("""(?i)^flashlight on$"""),
        Regex("""(?i)^torch on$""")
    )

    private val FLASHLIGHT_OFF_PATTERNS = listOf(
        Regex("""(?i)^turn off (?:the )?flashlight$"""),
        Regex("""(?i)^switch off (?:the )?flashlight$"""),
        Regex("""(?i)^flashlight off$"""),
        Regex("""(?i)^torch off$""")
    )

    // ── Response phrase pools ────────────────────────────────────────────────
    private val BATTERY_PHRASES = listOf("You're at %d%%.", "Battery is at %d%%.", "You've got %d%% battery left.")
    private val WEATHER_ACK = listOf("Let me check the weather.", "Checking it now.", "One sec, checking the weather.")
    private val OPEN_APP_ACK = listOf("Opening %s.", "Sure, opening %s.", "%s, coming right up.")
    private val WEB_SEARCH_ACK = listOf("Sure, I'll look that up.", "Let me check.", "On it.")
    private val LOCATION_ACK = listOf("You're at %s.", "Looks like you're at %s.", "Your current location is %s.")

    /**
     * Checks [prompt] against every local command type. Returns null if none
     * match, meaning it should fall through to normal Grok inference.
     */
    fun handle(
        context: Context,
        prompt: String,
        onLocationResult: ((String) -> Unit)? = null
    ): CommandResult? {
        val text = prompt.trim()
        Log.d(TAG, "handle() called with text=\"$text\"")

        if (BATTERY_PATTERNS.any { it.containsMatchIn(text) }) {
            val pct = getBatteryPercent(context)
            return CommandResult.Battery(BATTERY_PHRASES.random().format(pct))
        }

        if (text.equals("weather", ignoreCase = true)) {
            return CommandResult.Weather(
                WEATHER_ACK.random(),
                null
            )
        }

        for (pattern in WEATHER_PATTERNS) {
            val match = pattern.matchEntire(text) ?: continue
            val location = match.groupValues.getOrNull(1)?.trim()

            return CommandResult.Weather(
                WEATHER_ACK.random(),
                location?.takeIf { it.isNotBlank() }
            )
        }

        matchFirst(OPEN_APP_PATTERNS, text)?.let { rawName ->
            val appName = rawName.trim().trim('.', '!', '?', ' ')
            val launched = launchApp(context, appName)
            val displayName = appName.replaceFirstChar { it.uppercase() }
            val ack = if (launched) OPEN_APP_ACK.random().format(displayName)
                      else "I couldn't find an app called $displayName."
            return CommandResult.OpenApp(ack, appName, launched)
        }

        matchFirst(WEB_SEARCH_PATTERNS, text)?.let { query ->
            return CommandResult.WebSearch(WEB_SEARCH_ACK.random(), query.trim())
        }

        if (LOCATION_PATTERNS.any { it.containsMatchIn(text) }) {
            fetchCurrentLocation(context) { addressText ->
                onLocationResult?.invoke(
                    LOCATION_ACK.random().format(addressText)
                )
            }
            return CommandResult.Location("Checking your location…")
        }
        if (TIME_PATTERNS.any { it.matches(text) }) {
            val time = getCurrentTime()

            return CommandResult.Time(
                "It's $time."
            )
        }

        if (FLASHLIGHT_ON_PATTERNS.any { it.matches(text) }) {
            val success = setFlashlight(context, true)

            return CommandResult.Flashlight(
                if (success) {
                    "Flashlight is on."
                } else {
                    "I couldn't turn on the flashlight."
                }
            )
        }

        if (FLASHLIGHT_OFF_PATTERNS.any { it.matches(text) }) {
            val success = setFlashlight(context, false)

            return CommandResult.Flashlight(
                if (success) {
                    "Flashlight is off."
                } else {
                    "I couldn't turn off the flashlight."
                }
            )
        }

        Log.d(TAG, "No local pattern matched — falling through to Grok")
        return null
    }

    private fun matchFirst(patterns: List<Regex>, text: String): String? {
        for (p in patterns) {
            val m = p.find(text) ?: continue
            return m.groupValues.getOrNull(1)?.trim() ?: ""
        }
        return null
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat(
            "h:mm a",
            Locale.getDefault()
        ).format(Date())
    }

    private fun setFlashlight(
        context: Context,
        enabled: Boolean
    ): Boolean {
        return try {
            val cameraManager =
                context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics =
                    cameraManager.getCameraCharacteristics(id)

                characteristics.get(
                    CameraCharacteristics.FLASH_INFO_AVAILABLE
                ) == true
            } ?: return false

            cameraManager.setTorchMode(cameraId, enabled)
            true

        } catch (e: Exception) {
            Log.e(TAG, "Flashlight failed", e)
            false
        }
    }

    private fun fetchCurrentLocation(context: Context, onResult: (String) -> Unit) {
        val hasPermission =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            onResult("I don't have permission to check your location.")
            return
        }

        val client = LocationServices.getFusedLocationProviderClient(context)
        client.getCurrentLocation(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                null
            )
                .addOnSuccessListener { location ->
                if (location == null) {
                    onResult("I couldn't get a location fix right now.")
                    return@addOnSuccessListener
                }
                val address = try {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val results = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    results?.firstOrNull()?.let {
                        listOfNotNull(it.locality, it.adminArea).joinToString(", ")
                            .ifBlank { "lat ${location.latitude}, lon ${location.longitude}" }
                    } ?: "lat ${location.latitude}, lon ${location.longitude}"
                } catch (e: Exception) {
                    Log.e(TAG, "Geocoder failed", e)
                    "lat ${location.latitude}, lon ${location.longitude}"
                }
                onResult(address)
            }
            .addOnFailureListener {
                Log.e(TAG, "getLastLocation failed", it)
                onResult("I couldn't get your location.")
            }
    }

    // ── Device actions ───────────────────────────────────────────────────────

    private fun getBatteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /** Fuzzy-matches [spokenName] against installed launchable apps by label. */
    private fun launchApp(context: Context, spokenName: String): Boolean {
        val pm = context.packageManager
        val queryIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps = pm.queryIntentActivities(queryIntent, PackageManager.MATCH_ALL)
        Log.d(TAG, "launchApp: found ${apps.size} launchable apps, target=\"$spokenName\"")
        val target = spokenName.lowercase().trim()

        val exact = apps.firstOrNull { it.loadLabel(pm).toString().equals(target, ignoreCase = true) }
        val partial = apps.firstOrNull {
            val label = it.loadLabel(pm).toString().lowercase()
            label.contains(target) || target.contains(label)
        }
        val chosen = exact ?: partial ?: return false

        val launchIntent = pm.getLaunchIntentForPackage(chosen.activityInfo.packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }
}