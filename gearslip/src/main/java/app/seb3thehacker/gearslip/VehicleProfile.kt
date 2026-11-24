package app.seb3thehacker.gearslip

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Pixels of the video frame the car UI keeps clear on each side (the frame is painted, just not used). */
data class Insets(val top: Int = 0, val bottom: Int = 0, val left: Int = 0, val right: Int = 0) {
    companion object { val NONE = Insets() }
}

/**
 * Per-vehicle display tuning. Head units disagree about how they crop, offset and scale the
 * projected frame, so nothing here is applied unless a profile matches the connected unit.
 *
 * Profiles live in `vehicles.json` in the app's external files dir, so they can be edited and
 * pushed with adb without a rebuild:
 *
 *   adb push vehicles.json /sdcard/Android/data/app.seb3thehacker.gearslip/files/vehicles.json
 *
 * A profile matches when every key in [match] equals the head unit's value (case-insensitive);
 * keys it leaves out match anything. The first matching profile wins.
 */
data class VehicleProfile(
    val name: String,
    val match: Map<String, String>,
    /** Preferred video resolution, e.g. "800x480". Null keeps the default choice. */
    val resolution: String?,
    val insets: Insets,
)

object VehicleProfiles {

    private const val FILE = "vehicles.json"

    private val DEFAULT_JSON = """
        {
          "_comment": "Per-vehicle display tuning. match keys: headUnitName, carModel, carYear, headUnitMake, headUnitModel (see the log line 'head unit: ...'). insets are pixels of the video frame to keep clear.",
          "vehicles": [
            {
              "name": "2018 Dodge Challenger (Uconnect 4)",
              "match": { "carModel": "Dodge Challenger", "headUnitName": "Uconnect" },
              "resolution": "800x480",
              "insets": { "top": 30, "bottom": 30, "left": 0, "right": 0 }
            }
          ]
        }
    """.trimIndent()

    /** Reads the file, writing the default one first if there is none so there is something to edit. */
    fun load(context: Context): List<VehicleProfile> {
        val file = context.getExternalFilesDir(null)?.let { File(it, FILE) } ?: return emptyList()
        if (!file.isFile) runCatching { file.writeText(DEFAULT_JSON) }
        return runCatching { parse(file.readText()) }
            .onFailure { GearslipLog.e("could not read $FILE - ignoring vehicle profiles", it) }
            .getOrDefault(emptyList()) + BUILT_IN
    }

    /**
     * Profiles every install gets, after the file's own, so the file can still override them.
     * The file is only written once, so a profile added to [DEFAULT_JSON] later would never reach
     * anyone who already has one.
     */
    private val BUILT_IN = listOf(
        // Took every 1080p frame and showed none, while the Mach-E's SYNC 4 shows 1080p fine (issue #112).
        VehicleProfile(
            name = "Ford Transit (SYNC 4)",
            match = mapOf("carModel" to "Ford Transit", "headUnitModel" to "SYNC 4.0"),
            resolution = "1280x720",
            insets = Insets.NONE,
        ),
    )

    /** Writes [insets] into the profile called [name] in the file, leaving everything else as it was. */
    fun saveInsets(context: Context, name: String, insets: Insets): Boolean {
        val file = context.getExternalFilesDir(null)?.let { File(it, FILE) } ?: return false
        return runCatching {
            val root = JSONObject(file.readText())
            val vehicles = root.getJSONArray("vehicles")
            var found = false
            for (i in 0 until vehicles.length()) {
                val v = vehicles.getJSONObject(i)
                if (v.optString("name") == name) {
                    v.put(
                        "insets",
                        JSONObject()
                            .put("top", insets.top).put("bottom", insets.bottom)
                            .put("left", insets.left).put("right", insets.right),
                    )
                    found = true
                }
            }
            if (found) file.writeText(root.toString(2))
            found
        }.onFailure { GearslipLog.e("could not save insets to $FILE", it) }.getOrDefault(false)
    }

    fun parse(json: String): List<VehicleProfile> {
        val vehicles: JSONArray = JSONObject(json).optJSONArray("vehicles") ?: return emptyList()
        return (0 until vehicles.length()).map { i ->
            val v = vehicles.getJSONObject(i)
            val match = v.optJSONObject("match")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } }
                ?: emptyMap()
            val ins = v.optJSONObject("insets")
            VehicleProfile(
                name = v.optString("name", "vehicle $i"),
                match = match,
                resolution = v.optString("resolution").ifEmpty { null },
                insets = if (ins == null) Insets.NONE else Insets(
                    ins.optInt("top"), ins.optInt("bottom"), ins.optInt("left"), ins.optInt("right"),
                ),
            )
        }
    }

    fun find(profiles: List<VehicleProfile>, info: ServiceDiscovery.HeadUnitInfo): VehicleProfile? {
        val values = mapOf(
            "headUnitName" to info.headUnitName,
            "carModel" to info.carModel,
            "carYear" to info.carYear,
            "headUnitMake" to info.headUnitMake,
            "headUnitModel" to info.headUnitModel,
        )
        return profiles.firstOrNull { p ->
            p.match.isNotEmpty() && p.match.all { (k, want) -> values[k].equals(want, ignoreCase = true) }
        }
    }
}
