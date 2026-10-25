package camp.room.app

import camp.room.app.observability.*
import camp.room.generated.api.DefaultApi
import camp.room.generated.infrastructure.HttpResponse
import camp.room.generated.model.CartAdd
import camp.room.generated.model.CartQuantity
import camp.room.generated.model.CheckoutInput
import camp.room.generated.model.ControlInput
import camp.room.generated.model.NavigationInput
import camp.room.generated.model.OrderInput
import camp.room.generated.model.SceneInput
import camp.room.generated.model.TripInput
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** UI delivery facts, converted explicitly to generated request DTOs at the API boundary. */
data class DeliveryDetails(val destination:String,val recipientName:String,val address:String,
    val siteReference:String,val campsiteId:String?,val checkIn:String?,val checkOut:String?)

/** Every HTTP operation is generated from apps/api/openapi.json; this class owns UI mapping only. */
@Singleton
class CampRepository @Inject constructor(private val api:DefaultApi,private val json:Json,private val telemetry:Telemetry) {
    private suspend fun <T> checked(response:HttpResponse<T>):T {
        if(!response.success) {
            val detail=runCatching { val value=JSONObject(response.response.bodyAsText()).opt("detail"); if(value is String)value else "" }.getOrDefault("")
            throw IllegalStateException(detail.ifBlank { CampText.get(R.string.camp_we_couldn_t_complete_the_request)+" (HTTP ${response.status})" })
        }
        return response.body()
    }
    private suspend fun <T> call(operation:ApiOperation, request:suspend ()->HttpResponse<T>):T {
        val start=System.nanoTime()
        var status:Int?=null
        try {
            val response=request();status=response.status
            val body=checked(response)
            telemetry.record(Event.API_COMPLETED,Dimensions(api=operation,outcome=Outcome.SUCCESS,durationMs=(System.nanoTime()-start)/1_000_000,statusCode=status))
            return body
        } catch(cancelled:CancellationException) {
            telemetry.record(Event.API_COMPLETED,Dimensions(api=operation,outcome=Outcome.CANCELLED,durationMs=(System.nanoTime()-start)/1_000_000,statusCode=status))
            throw cancelled
        } catch(error:Exception) {
            telemetry.failure(error,Dimensions(api=operation,durationMs=(System.nanoTime()-start)/1_000_000,statusCode=status))
            throw error
        }
    }
    private inline fun <reified T> objectOf(value:T)=JSONObject(json.encodeToString(value))
    suspend fun bootstrap():Bootstrap=Bootstrap.parse(objectOf(call(ApiOperation.BOOTSTRAP) { api.getBootstrap() }))
    suspend fun campsites(query:Map<String,String>):List<Campsite> {
        val response=call(ApiOperation.SEARCH_CAMPSITES) { api.searchCampsites(region=query["region"]?:"",checkIn=query["checkIn"],checkOut=query["checkOut"],
            guests=query["guests"]?.toIntOrNull()?:2,carCamping=query["carCamping"]?.toBooleanStrictOrNull(),facilities=query["facilities"]?:"") }
        return response.items.map { Campsite.parse(objectOf(it)) }
    }
    suspend fun saveTrip(campsiteId:String,checkIn:String,checkOut:String,guests:Int) {
        call(ApiOperation.SAVE_TRIP) { api.saveTrip(TripInput(campsiteId=campsiteId,checkIn=checkIn,checkOut=checkOut,guests=guests)) }
    }
    suspend fun weather(campsiteId:String,checkIn:String,checkOut:String):Weather =
        Weather.parse(objectOf(call(ApiOperation.WEATHER) { api.getWeather(campsiteId=campsiteId,checkIn=checkIn,checkOut=checkOut) }))
    suspend fun addProduct(productId:String) { call(ApiOperation.ADD_CART_ITEM) { api.addCartItem(CartAdd(productId=productId,quantity=1)) } }
    suspend fun quantity(productId:String,quantity:Int) {
        if(quantity<=0)call(ApiOperation.DELETE_CART_ITEM) { api.deleteCartItem(productId) } else call(ApiOperation.UPDATE_CART_ITEM) { api.updateCartItem(productId,CartQuantity(quantity=quantity)) }
    }
    suspend fun quote(details:DeliveryDetails):Quote {
        val request=CheckoutInput(destination=if(details.destination=="home")CheckoutInput.Destination.HOME else CheckoutInput.Destination.CAMPSITE,
            recipientName=details.recipientName,address=details.address,siteReference=details.siteReference,campsiteId=details.campsiteId,checkIn=details.checkIn,checkOut=details.checkOut)
        return Quote.parse(objectOf(call(ApiOperation.QUOTE_CHECKOUT) { api.quoteCheckout(request) }))
    }
    suspend fun order(details:DeliveryDetails,idempotencyKey:String):Order {
        val request=OrderInput(destination=if(details.destination=="home")OrderInput.Destination.HOME else OrderInput.Destination.CAMPSITE,
            idempotencyKey=idempotencyKey,recipientName=details.recipientName,address=details.address,siteReference=details.siteReference,
            campsiteId=details.campsiteId,checkIn=details.checkIn,checkOut=details.checkOut)
        return Order.parse(objectOf(call(ApiOperation.CREATE_ORDER) { api.createOrder(request) }))
    }
    // Demo flavor translates the native vehicle/navigation port to typed simulator APIs.
    suspend fun demoVehicle():JSONObject=objectOf(call(ApiOperation.VEHICLE) { api.getVehicle() })
    suspend fun demoScene(sceneId:String):JSONObject {
        val scene=SceneInput.SceneId.entries.firstOrNull{it.value==sceneId}?:throw IllegalArgumentException("Unknown scene")
        return objectOf(call(ApiOperation.SCENE) { api.applyScene(SceneInput(sceneId=scene)) })
    }
    suspend fun demoControl(body:JSONObject):JSONObject {
        val raw=body.opt("value")
        val value=when(raw){is Boolean->JsonPrimitive(raw);is Number->JsonPrimitive(raw);else->throw IllegalArgumentException("Control requires boolean or number")}
        val zone=when(body.optString("zone")){"front"->ControlInput.Zone.FRONT;"rear"->ControlInput.Zone.REAR;else->null}
        return objectOf(call(ApiOperation.CONTROL) { api.controlVehicle(ControlInput(control=body.getString("control"),value=value,zone=zone)) })
    }
    suspend fun demoNavigationPreview(campsiteId:String):JSONObject=objectOf(call(ApiOperation.NAVIGATION_PREVIEW) { api.previewNavigation(NavigationInput(campsiteId)) })
    suspend fun demoNavigationStart(campsiteId:String):JSONObject=objectOf(call(ApiOperation.NAVIGATION_START) { api.startNavigation(NavigationInput(campsiteId)) })
}
