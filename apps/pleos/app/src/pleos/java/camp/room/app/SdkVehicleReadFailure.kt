package camp.room.app

import ai.pleos.playground.vehicle.constant.ErrorCode
import ai.pleos.playground.vehicle.callback.exception.a as SdkVehicleException

/** Diagnostic categories only; these do not change read outcomes or retry policy. */
internal enum class SdkVehicleReadFailureClass {
    INTERNAL_SERVICE,
    SERVICE_CONNECTION_FAILURE,
    DRIVING_RESTRICTED,
    UNSUPPORTED_PROPERTY,
    UNSUPPORTED_VALUE_TYPE,
    UNSUPPORTED_AREA,
    EMPTY_RESULT,
    SECURITY_PERMISSION,
    UNDEFINED_SDK_ERROR,
    UNKNOWN_SDK_ERROR,
    NON_VEHICLE_EXCEPTION,
}

/** Retains only the SDK's public error enum, never an exception or its message. */
internal data class SdkVehicleReadFailure(
    val errorCode: ErrorCode?,
    val failureClass: SdkVehicleReadFailureClass,
) {
    fun traceFields(): Array<Pair<String, Any?>> =
        arrayOf(
            "errorCode" to errorCode?.name,
            "errorCodeId" to errorCode?.id,
            "failureClass" to failureClass.name.lowercase(),
        )

    companion object {
        fun from(error: Exception): SdkVehicleReadFailure {
            // Vehicle 2.3.2 publishes the documented VehicleException.errorCode
            // as public class a and accessor a(); original Kotlin metadata is absent.
            val code = (error as? SdkVehicleException)?.a()
            val classification =
                when (code) {
                    ErrorCode.ERROR_INTERNAL_SERVICE -> SdkVehicleReadFailureClass.INTERNAL_SERVICE
                    ErrorCode.ERROR_SERVICE_CONNECTION_FAILURE -> SdkVehicleReadFailureClass.SERVICE_CONNECTION_FAILURE
                    ErrorCode.ERROR_DRIVING_RESTRICTED -> SdkVehicleReadFailureClass.DRIVING_RESTRICTED
                    ErrorCode.ERROR_NOT_SUPPORTED_PROPERTY -> SdkVehicleReadFailureClass.UNSUPPORTED_PROPERTY
                    ErrorCode.ERROR_NOT_SUPPORTED_VALUE_TYPE -> SdkVehicleReadFailureClass.UNSUPPORTED_VALUE_TYPE
                    ErrorCode.ERROR_NOT_SUPPORTED_AREA -> SdkVehicleReadFailureClass.UNSUPPORTED_AREA
                    ErrorCode.ERROR_EMPTY_RESULT -> SdkVehicleReadFailureClass.EMPTY_RESULT
                    ErrorCode.ERROR_SECURITY_PERMISSION -> SdkVehicleReadFailureClass.SECURITY_PERMISSION
                    ErrorCode.ERROR_UNDEFINED -> SdkVehicleReadFailureClass.UNDEFINED_SDK_ERROR
                    null -> SdkVehicleReadFailureClass.NON_VEHICLE_EXCEPTION
                    else -> SdkVehicleReadFailureClass.UNKNOWN_SDK_ERROR
                }
            return SdkVehicleReadFailure(code, classification)
        }
    }
}
