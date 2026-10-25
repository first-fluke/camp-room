package camp.room.generated

import camp.room.client.KtorApiJson
import camp.room.generated.model.Bootstrap
import java.math.BigDecimal
import camp.room.generated.api.DefaultApi
import camp.room.generated.model.CartAdd
import camp.room.generated.model.ControlInput
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.*
import org.junit.Test

class GeneratedKtorContractTest {
    private val codec=KtorApiJson.create()
    @Test fun generatedTypedRequestAndResponseUseKotlinxCodec()=runBlocking {
        var payload=""
        val engine=MockEngine { request ->
            assertEquals("/api/v1/cart/items",request.url.encodedPath)
            payload=when(val body=request.body){is TextContent->body.text;is OutgoingContent.ByteArrayContent->body.bytes().decodeToString();else->error("Unexpected request body ${body::class}")}
            respond("""{"items":[],"subtotal":0,"shipping":3000,"total":3000}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,ContentType.Application.Json.toString()))
        }
        val api=DefaultApi(baseUrl="http://localhost",httpClientEngine=engine,httpClientConfig={it.install(ContentNegotiation){json(codec)}})
        val response=api.addCartItem(CartAdd(productId="lamp",quantity=2))
        assertTrue(response.success)
        assertEquals(3000,response.body().total)
        assertTrue(payload.contains("\"productId\":\"lamp\""))
        assertTrue(payload.contains("\"quantity\":2"))
        engine.close()
    }
    @Test fun scalarUnionIsTypedBooleanNotAnEmptyObject() {
        val body=codec.encodeToString(ControlInput.serializer(),ControlInput(control="readingLight",value=JsonPrimitive(true),zone=ControlInput.Zone.FRONT))
        assertTrue(body.contains("\"value\":true"))
        assertFalse(body.contains("\"value\":{}"))
        val roundtrip=codec.decodeFromString(ControlInput.serializer(),body)
        assertEquals(JsonPrimitive(true),roundtrip.value)
    }
    @Test fun actualDemoBootstrapDecodesAndReencodesExactNumbersAndNulls()=runBlocking {
        // Actual clean demo API response; no credentials, orders or personal recipient data.
        val fixture=checkNotNull(javaClass.getResource("/fixtures/bootstrap-demo.json")).readText()
        val engine=MockEngine { request ->
            assertEquals("/api/v1/bootstrap",request.url.encodedPath)
            respond(fixture,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,ContentType.Application.Json.toString()))
        }
        val api=DefaultApi(baseUrl="http://localhost",httpClientEngine=engine,httpClientConfig={it.install(ContentNegotiation){json(codec)}})
        val response=api.getBootstrap()
        assertTrue(response.success)
        val decoded=response.body()
        assertEquals(Bootstrap.Mode.DEMO,decoded.mode)
        assertTrue(decoded.campsites.isNotEmpty())
        // App mapping also encodes these DTOs, so exercise both directions with the same DI codec.
        val encoded=codec.encodeToString(Bootstrap.serializer(),decoded)
        assertEquals(decoded,codec.decodeFromString(Bootstrap.serializer(),encoded))
        val precise=BigDecimal("37.12345678901234567890")
        val variant=decoded.copy(campsites=listOf(decoded.campsites.first().copy(latitude=precise)),vehicle=decoded.vehicle.copy(soc=null))
        val tree=codec.parseToJsonElement(codec.encodeToString(Bootstrap.serializer(),variant)).jsonObject
        val latitude=tree.getValue("campsites").jsonArray.first().jsonObject.getValue("latitude").jsonPrimitive
        assertFalse(latitude.isString)
        assertEquals(precise.toString(),latitude.content)
        assertEquals(JsonNull,tree.getValue("vehicle").jsonObject.getValue("soc"))
        val price:Int=decoded.products.first().price
        val encodedPrice=tree.getValue("products").jsonArray.first().jsonObject.getValue("price").jsonPrimitive
        assertFalse(encodedPrice.isString)
        assertEquals(price,encodedPrice.int)
        engine.close()
    }
}
