package dev.aarso.mail.intel

import dev.aarso.mail.model.Category
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * schema.org markup that airlines, hotels, shops and carriers embed in mail (the "Gmail markup"
 * vocabulary). When it is present it is the most reliable signal there is: the sender has told us,
 * in a machine-readable way, exactly what this message is.
 */
data class StructuredCard(
    val type: String,
    val category: Category,
    val title: String,
    val fields: Map<String, String>,
    /** Epoch millis of the thing itself (departure, check-in, delivery, event start), if stated. */
    val at: Long? = null,
)

object StructuredData {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val CATEGORY_OF = mapOf(
        "FlightReservation" to Category.TRAVEL, "LodgingReservation" to Category.TRAVEL, "TrainReservation" to Category.TRAVEL,
        "BusReservation" to Category.TRAVEL, "RentalCarReservation" to Category.TRAVEL, "TaxiReservation" to Category.TRAVEL,
        "BoatReservation" to Category.TRAVEL, "FoodEstablishmentReservation" to Category.EVENTS, "EventReservation" to Category.EVENTS,
        "Order" to Category.RECEIPTS, "Invoice" to Category.RECEIPTS, "ParcelDelivery" to Category.SHIPPING,
    )

    fun cards(jsonLdBlocks: List<String>): List<StructuredCard> = jsonLdBlocks.flatMap { block ->
        val root = runCatching { json.parseToJsonElement(block) }.getOrNull() ?: return@flatMap emptyList()
        flatten(root).mapNotNull { card(it) }
    }

    private fun flatten(e: JsonElement): List<JsonObject> = when (e) {
        is JsonArray -> e.flatMap { flatten(it) }
        is JsonObject -> {
            val graph = e["@graph"]
            if (graph != null) flatten(graph) else listOf(e)
        }
        else -> emptyList()
    }

    private fun card(o: JsonObject): StructuredCard? {
        val type = o.str("@type") ?: return null
        val category = CATEGORY_OF[type] ?: return null
        val fields = LinkedHashMap<String, String>()
        var at: Long? = null
        val title = when (type) {
            "FlightReservation" -> {
                val f = o.obj("reservationFor")
                val airline = f?.obj("airline")?.str("iataCode") ?: f?.obj("airline")?.str("name").orEmpty()
                val number = f?.str("flightNumber").orEmpty()
                val from = f?.obj("departureAirport")?.str("iataCode") ?: f?.obj("departureAirport")?.str("name")
                val to = f?.obj("arrivalAirport")?.str("iataCode") ?: f?.obj("arrivalAirport")?.str("name")
                f?.str("departureTime")?.let { fields["departs"] = it; at = parseTime(it) }
                o.str("reservationNumber")?.let { fields["confirmation"] = it }
                o.str("checkinUrl")?.let { fields["check-in"] = it }
                listOfNotNull("$airline $number".trim().ifEmpty { null }, if (from != null && to != null) "$from → $to" else null).joinToString(" · ")
            }
            "LodgingReservation" -> {
                val hotel = o.obj("reservationFor")?.str("name").orEmpty()
                o.str("checkinTime")?.let { fields["check-in"] = it; at = parseTime(it) }
                o.str("checkoutTime")?.let { fields["check-out"] = it }
                o.str("reservationNumber")?.let { fields["confirmation"] = it }
                hotel.ifEmpty { "Hotel reservation" }
            }
            "TrainReservation", "BusReservation", "BoatReservation" -> {
                val f = o.obj("reservationFor")
                f?.str("departureTime")?.let { fields["departs"] = it; at = parseTime(it) }
                o.str("reservationNumber")?.let { fields["confirmation"] = it }
                val from = f?.obj("departureStation")?.str("name") ?: f?.obj("departureBusStop")?.str("name")
                val to = f?.obj("arrivalStation")?.str("name") ?: f?.obj("arrivalBusStop")?.str("name")
                if (from != null && to != null) "$from → $to" else type.removeSuffix("Reservation")
            }
            "RentalCarReservation" -> {
                o.str("pickupTime")?.let { fields["pickup"] = it; at = parseTime(it) }
                o.obj("reservationFor")?.obj("rentalCompany")?.str("name") ?: "Car rental"
            }
            "TaxiReservation" -> { o.str("pickupTime")?.let { at = parseTime(it) }; "Ride" }
            "EventReservation", "FoodEstablishmentReservation" -> {
                val ev = o.obj("reservationFor")
                (ev?.str("startDate") ?: o.str("startTime"))?.let { fields["starts"] = it; at = parseTime(it) }
                ev?.obj("location")?.str("name")?.let { fields["venue"] = it }
                ev?.str("name") ?: "Reservation"
            }
            "Order" -> {
                val merchant = o.obj("merchant")?.str("name") ?: o.obj("seller")?.str("name")
                o.str("orderNumber")?.let { fields["order"] = it }
                val price = o.str("price") ?: o.obj("priceSpecification")?.str("price") ?: o.str("totalPrice")
                val currency = o.str("priceCurrency") ?: o.obj("priceSpecification")?.str("priceCurrency")
                if (price != null) fields["total"] = listOfNotNull(currency, price).joinToString(" ")
                merchant ?: "Order"
            }
            "Invoice" -> {
                o.str("paymentDueDate")?.let { fields["due"] = it; at = parseTime(it) }
                o.obj("totalPaymentDue")?.let { t -> fields["total"] = listOfNotNull(t.str("priceCurrency"), t.str("price")).joinToString(" ") }
                o.obj("provider")?.str("name") ?: "Invoice"
            }
            "ParcelDelivery" -> {
                o.str("trackingNumber")?.let { fields["tracking"] = it }
                o.str("trackingUrl")?.let { fields["track"] = it }
                o.str("expectedArrivalUntil")?.let { fields["arrives"] = it; at = parseTime(it) }
                o.obj("carrier")?.str("name") ?: o.obj("provider")?.str("name") ?: "Package"
            }
            else -> type
        }
        return StructuredCard(type, category, title, fields, at)
    }

    private fun parseTime(s: String): Long? = runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.LocalDate.parse(s.take(10)).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()

    private fun JsonObject.str(k: String): String? = when (val v = this[k]) {
        is JsonPrimitive -> v.contentOrNull
        is JsonObject -> v.str("name") ?: v.str("@id")
        is JsonArray -> (v.firstOrNull() as? JsonPrimitive)?.contentOrNull
        else -> null
    }

    private fun JsonObject.obj(k: String): JsonObject? = when (val v = this[k]) {
        is JsonObject -> v
        is JsonArray -> v.firstOrNull() as? JsonObject
        else -> null
    }
}
