package dev.aarso.mail.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Rules saved before v2 → rules as v2 stores them, without losing or changing anything:
 *  - every old field is kept exactly as it was (the new fields all have defaults, so the old JSON would also read back unchanged);
 *  - `order` is taken from the store's sort column, so the list keeps its order;
 *  - `runLocation` is pinned to DEVICE, so a rule that always ran on the phone does not start running somewhere else;
 *  - `confirmed` is true for a rule that already forwards mail: the person saved it knowingly, and quietly stopping it would be a change in behaviour.
 */
object RuleMigration {

    fun upgrade(oldJson: String, sortOrder: Int): String {
        val o = Json.parseToJsonElement(oldJson).jsonObject
        val forwards = o["actions"]?.jsonArray?.any { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "forward" } == true
        val m = LinkedHashMap(o)
        m.putIfAbsent("order", JsonPrimitive(sortOrder))
        m.putIfAbsent("runLocation", JsonPrimitive("DEVICE"))
        m.putIfAbsent("confirmed", JsonPrimitive(forwards))
        return JsonObject(m).toString()
    }
}
