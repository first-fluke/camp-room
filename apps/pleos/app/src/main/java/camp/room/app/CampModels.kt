package camp.room.app

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.text(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback)

internal fun JSONObject.nullNumber(key: String): Double? =
    if (isNull(key) || !has(key)) null else optDouble(key).takeIf { it.isFinite() }

internal fun JSONObject.nullBool(key: String): Boolean? =
    if (isNull(key) || !has(key)) null else optBoolean(key)

internal fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()

internal fun JSONObject.strings(key: String): List<String> =
    optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()

internal fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONObject.objects(key: String): List<JSONObject> =
    optJSONArray(key)?.objects() ?: emptyList()

data class Availability(
    val status: String,
    val remaining: Int?,
    val coverage: String,
    val checkedAt: String,
    val provider: String,
    val demo: Boolean,
) {
    val label: String
        get() =
            when {
                status == "unknown" -> "확인 필요"
                status == "unavailable" -> "예약 마감"
                remaining != null -> "잔여 ${remaining}자리"
                status == "limited" -> "마감 임박"
                else -> "예약 가능"
            }

    companion object {
        fun parse(j: JSONObject) =
            Availability(
                j.text("status", "unknown"),
                j.nullNumber("remaining")?.toInt(),
                j.text("coverage"),
                j.text("checkedAt"),
                j.text("provider"),
                j.optBoolean("demo", true),
            )
    }
}

data class Campsite(
    val id: String,
    val name: String,
    val region: String,
    val address: String,
    val description: String,
    val price: Int?,
    val facilities: List<String>,
    val carCamping: Boolean?,
    val evCharging: Boolean?,
    val rules: List<String>,
    val checkInTime: String,
    val checkOutTime: String,
    val acceptsParcels: Boolean,
    val receivingLocation: String,
    val receivingInstructions: String,
    val availability: Availability,
    val source: String,
    val sourceMode: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    companion object {
        fun parse(j: JSONObject): Campsite {
            val delivery = j.obj("delivery")
            return Campsite(
                j.text("id"),
                j.text("name"),
                j.text("region"),
                j.text("address"),
                j.text("description"),
                j.nullNumber("nightlyPrice")?.toInt(),
                j.strings("facilities"),
                j.nullBool("carCampingAllowed"),
                j.nullBool("evChargingAllowed"),
                j.strings("rules"),
                j.text("checkInTime"),
                j.text("checkOutTime"),
                delivery.optBoolean("acceptsParcels"),
                delivery.text("location"),
                delivery.text("instructions"),
                Availability.parse(j.obj("availability")),
                j.obj("source").text("name"),
                j.obj("source").text("mode", "demo"),
                j.nullNumber("latitude"),
                j.nullNumber("longitude"),
            )
        }
    }
}

data class Trip(
    val campsiteId: String,
    val checkIn: String,
    val checkOut: String,
    val guests: Int,
    val navigationStarted: Boolean,
) {
    companion object {
        fun parse(j: JSONObject) =
            Trip(
                j.text("campsiteId"),
                j.text("checkIn"),
                j.text("checkOut"),
                j.optInt("guests", 2),
                j.optBoolean("navigationStarted"),
            )
    }
}

data class Forecast(val time: String, val temperature: Double?, val condition: String)

data class Weather(
    val source: String,
    val updatedAt: String,
    val summary: String,
    val low: Double?,
    val rain: Double?,
    val forecast: List<Forecast>,
    val demo: Boolean,
    val status: String,
    val message: String,
) {
    companion object {
        fun parse(j: JSONObject) =
            Weather(
                j.text("source"),
                j.text("updatedAt"),
                j.text("summary"),
                j.nullNumber("overnightLow"),
                j.nullNumber("rainProbability"),
                j.objects("forecast").map {
                    Forecast(it.text("time"), it.nullNumber("temperature"), it.text("condition"))
                },
                j.optBoolean("demo", true),
                j.text("status", "unavailable"),
                j.text("message"),
            )
    }
}

data class CabinZone(val reading: Boolean?, val ambient: Int?, val blind: Int?) {
    companion object {
        fun parse(j: JSONObject) =
            CabinZone(
                j.nullBool("readingLight"),
                j.nullNumber("ambientBrightness")?.toInt(),
                j.nullNumber("blindLevel")?.toInt(),
            )
    }
}

data class Vehicle(
    val soc: Double?,
    val measuredTemperature: Double?,
    val targetTemperature: Double?,
    val front: CabinZone,
    val rear: CabinZone,
    val doorsClosed: Boolean?,
    val windowsClosed: Boolean?,
    val motionState: String,
    val sceneId: String,
    val updatedAt: String,
    val mode: String,
) {
    companion object {
        fun parse(j: JSONObject) =
            Vehicle(
                j.nullNumber("soc"),
                j.nullNumber("cabinTemperature"),
                j.nullNumber("targetTemperature"),
                CabinZone.parse(j.obj("front")),
                CabinZone.parse(j.obj("rear")),
                j.nullBool("doorsClosed"),
                j.nullBool("windowsClosed"),
                j.text("motionState", "unknown"),
                j.text("lastSceneId"),
                j.text("updatedAt"),
                j.text("mode", "demo"),
            )
    }
}

data class CommandResult(
    val control: String,
    val status: String,
    val confirmation: String,
    val observed: String,
    val message: String,
) {
    companion object {
        fun parse(j: JSONObject) =
            CommandResult(
                j.text("control"),
                j.text("status"),
                j.text("confirmation"),
                j.text("observed", "미관측"),
                j.text("message"),
            )
    }
}

data class Product(
    val id: String,
    val name: String,
    val price: Int,
    val stock: Int,
    val category: String,
    val reason: String,
) {
    companion object {
        fun parse(j: JSONObject) =
            Product(
                j.text("id"),
                j.text("name"),
                j.optInt("price"),
                j.optInt("stock"),
                j.text("category"),
                j.text("recommendationReason"),
            )
    }
}

data class CartItem(
    val id: String,
    val name: String,
    val quantity: Int,
    val unitPrice: Int,
    val lineTotal: Int,
) {
    companion object {
        fun parse(j: JSONObject) =
            CartItem(
                j.text("productId"),
                j.text("name"),
                j.optInt("quantity"),
                j.optInt("unitPrice"),
                j.optInt("lineTotal"),
            )
    }
}

data class Cart(val items: List<CartItem>, val subtotal: Int, val shipping: Int, val total: Int) {
    val count: Int
        get() = items.sumOf { it.quantity }

    companion object {
        fun parse(j: JSONObject) =
            Cart(
                j.objects("items").map(CartItem::parse),
                j.optInt("subtotal"),
                j.optInt("shipping"),
                j.optInt("total"),
            )
    }
}

data class Quote(
    val eligible: Boolean,
    val reason: String,
    val eta: String,
    val conditions: List<String>,
    val shipping: Int,
    val total: Int,
) {
    companion object {
        fun parse(j: JSONObject) =
            Quote(
                j.optBoolean("eligible"),
                j.text("reason"),
                j.text("eta"),
                j.strings("receivingConditions"),
                j.optInt("shipping"),
                j.optInt("total"),
            )
    }
}

data class Order(
    val id: String,
    val total: Int,
    val destination: String,
    val eta: String,
    val recipient: String,
    val address: String,
    val siteReference: String,
    val createdAt: String,
) {
    companion object {
        fun parse(j: JSONObject) =
            Order(
                j.text("id"),
                j.optInt("total"),
                j.text("destination"),
                j.text("eta"),
                j.text("recipientName"),
                j.text("address"),
                j.text("siteReference"),
                j.text("createdAt"),
            )
    }
}

data class RoutePreview(
    val distance: Double?,
    val minutes: Int?,
    val consumption: Double?,
    val arrivalSoc: Double?,
    val chargingStop: Boolean,
) {
    companion object {
        fun parse(j: JSONObject) =
            RoutePreview(
                j.nullNumber("distanceKm"),
                j.nullNumber("durationMinutes")?.toInt(),
                j.nullNumber("consumptionKwh"),
                j.nullNumber("arrivalSoc"),
                j.optBoolean("chargingStop"),
            )
    }
}

data class ProviderStatus(
    val name: String,
    val status: String,
    val mode: String,
    val message: String,
)

data class Bootstrap(
    val mode: String,
    val campsites: List<Campsite>,
    val trip: Trip?,
    val weather: Weather,
    val vehicle: Vehicle,
    val products: List<Product>,
    val cart: Cart,
    val orders: List<Order>,
    val providers: List<ProviderStatus>,
) {
    companion object {
        fun parse(j: JSONObject) =
            Bootstrap(
                j.text("mode", "demo"),
                j.objects("campsites").map(Campsite::parse),
                j.optJSONObject("trip")?.let(Trip::parse),
                Weather.parse(j.obj("weather")),
                Vehicle.parse(j.obj("vehicle")),
                j.objects("products").map(Product::parse),
                Cart.parse(j.obj("cart")),
                j.objects("orders").map(Order::parse),
                j.objects("providers").map {
                    ProviderStatus(
                        it.text("name"),
                        it.text("status"),
                        it.text("mode"),
                        it.text("message"),
                    )
                },
            )
    }
}
