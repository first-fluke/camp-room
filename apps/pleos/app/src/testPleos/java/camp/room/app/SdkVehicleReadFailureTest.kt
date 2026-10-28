package camp.room.app

import ai.pleos.playground.vehicle.constant.ErrorCode
import ai.pleos.playground.vehicle.callback.exception.a as SdkVehicleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdkVehicleReadFailureTest {
    @Test
    fun publicSdkCodesRetainTheirIdentityAndDistinctClassification() {
        val expected = mapOf(
            ErrorCode.ERROR_INTERNAL_SERVICE to SdkVehicleReadFailureClass.INTERNAL_SERVICE,
            ErrorCode.ERROR_SERVICE_CONNECTION_FAILURE to SdkVehicleReadFailureClass.SERVICE_CONNECTION_FAILURE,
            ErrorCode.ERROR_DRIVING_RESTRICTED to SdkVehicleReadFailureClass.DRIVING_RESTRICTED,
            ErrorCode.ERROR_NOT_SUPPORTED_PROPERTY to SdkVehicleReadFailureClass.UNSUPPORTED_PROPERTY,
            ErrorCode.ERROR_NOT_SUPPORTED_VALUE_TYPE to SdkVehicleReadFailureClass.UNSUPPORTED_VALUE_TYPE,
            ErrorCode.ERROR_NOT_SUPPORTED_AREA to SdkVehicleReadFailureClass.UNSUPPORTED_AREA,
            ErrorCode.ERROR_EMPTY_RESULT to SdkVehicleReadFailureClass.EMPTY_RESULT,
            ErrorCode.ERROR_SECURITY_PERMISSION to SdkVehicleReadFailureClass.SECURITY_PERMISSION,
            ErrorCode.ERROR_UNDEFINED to SdkVehicleReadFailureClass.UNDEFINED_SDK_ERROR,
        )
        assertEquals(ErrorCode.values().toSet(), expected.keys)
        for ((code, classification) in expected) {
            val failure = SdkVehicleReadFailure.from(SdkVehicleException(code.id.toString()))
            assertEquals(code, failure.errorCode)
            assertEquals(classification, failure.failureClass)
            assertEquals(mapOf(
                "errorCode" to code.name,
                "errorCodeId" to code.id,
                "failureClass" to classification.name.lowercase(),
            ), failure.traceFields().toMap())
        }
    }

    @Test
    fun anObfuscatedClassNameAndCodeLikeMessageDoNotImplyUnsupported() {
        class a : Exception("ERROR_NOT_SUPPORTED_PROPERTY 60001")

        val failure = SdkVehicleReadFailure.from(a())

        assertNull(failure.errorCode)
        assertEquals(SdkVehicleReadFailureClass.NON_VEHICLE_EXCEPTION, failure.failureClass)
        assertEquals(mapOf(
            "errorCode" to null,
            "errorCodeId" to null,
            "failureClass" to "non_vehicle_exception",
        ), failure.traceFields().toMap())
    }

    @Test
    fun diagnosticsDoNotReadOrRetainAnArbitraryExceptionMessageOrString() {
        val exception = object : Exception() {
            override val message: String get() = error("Message must not be read")
            override fun toString(): String = error("Exception must not be rendered")
        }

        val failure = SdkVehicleReadFailure.from(exception)

        assertEquals(SdkVehicleReadFailureClass.NON_VEHICLE_EXCEPTION, failure.failureClass)
        assertEquals(setOf("errorCode", "errorCodeId", "failureClass"), failure.traceFields().toMap().keys)
        assertNull(failure.traceFields().toMap()["errorCode"])
        assertNull(failure.traceFields().toMap()["errorCodeId"])
    }

    @Test
    fun unrecognizedSdkPayloadsExposeOnlyThePublicUndefinedCode() {
        for (payload in listOf(null, "", "-123456", "vehicle=private-id; value=60003")) {
            val failure = SdkVehicleReadFailure.from(SdkVehicleException(payload))

            assertEquals(ErrorCode.ERROR_UNDEFINED, failure.errorCode)
            assertEquals(SdkVehicleReadFailureClass.UNDEFINED_SDK_ERROR, failure.failureClass)
            assertEquals(mapOf(
                "errorCode" to "ERROR_UNDEFINED",
                "errorCodeId" to ErrorCode.ERROR_UNDEFINED.id,
                "failureClass" to "undefined_sdk_error",
            ), failure.traceFields().toMap())
        }
    }
}
