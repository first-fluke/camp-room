package camp.room.client

import java.math.BigDecimal
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/** Shared by the actual Android DI graph and generated-client contract tests. */
object KtorApiJson {
    fun create():Json=Json {
        ignoreUnknownKeys=true
        encodeDefaults=true
        explicitNulls=true
        // The pinned jvm-ktor generator marks OpenAPI number fields @Contextual BigDecimal.
        serializersModule=SerializersModule { contextual(BigDecimal::class,JsonDecimalSerializer) }
    }
}

/** Exact JSON numbers, never quoted strings or Double conversions. Integer DTOs stay unchanged. */
@OptIn(ExperimentalSerializationApi::class)
private object JsonDecimalSerializer:KSerializer<BigDecimal> {
    override val descriptor=PrimitiveSerialDescriptor("java.math.BigDecimal",PrimitiveKind.DOUBLE)
    override fun deserialize(decoder:Decoder):BigDecimal {
        val input=decoder as? JsonDecoder ?: throw SerializationException("Expected JSON decoder")
        val number=input.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("Expected JSON number")
        if(number.isString)throw SerializationException("Expected JSON number")
        return number.content.toBigDecimalOrNull() ?: throw SerializationException("Expected JSON number")
    }
    override fun serialize(encoder:Encoder,value:BigDecimal) {
        val output=encoder as? JsonEncoder ?: throw SerializationException("Expected JSON encoder")
        output.encodeJsonElement(JsonUnquotedLiteral(value.toString()))
    }
}
