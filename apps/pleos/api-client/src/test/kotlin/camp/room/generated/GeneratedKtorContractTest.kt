package camp.room.generated

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
import org.junit.Assert.*
import org.junit.Test

class GeneratedKtorContractTest {
    private val codec=Json{ignoreUnknownKeys=true;encodeDefaults=true;explicitNulls=true}
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
}
