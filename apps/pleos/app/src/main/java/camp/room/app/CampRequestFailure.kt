package camp.room.app

/** API failures carry only allowlisted resource IDs into presentation and telemetry. */
internal class CampRequestFailure private constructor(
    statusCode: Int,
    private val messageResources: List<Int>,
) : IllegalStateException("API request failed (HTTP $statusCode)") {
    fun localizedMessage(): String = localize(messageResources)

    companion object {
        // Exact reason fragments joined by apps/api/app/service.py::_quote.
        private val quoteReasons = linkedMapOf(
            "장바구니에 준비물을 먼저 담아 주세요." to R.string.request_error_empty_cart,
            "수령인 이름을 입력해 주세요." to R.string.camp_enter_the_recipient_s_name,
            "자택 배송 주소를 입력해 주세요." to R.string.camp_enter_your_home_delivery_address,
            "배송받을 캠핑장을 선택해 주세요." to R.string.request_error_delivery_campsite,
            "이 캠핑장은 택배 수령을 지원하지 않습니다. 자택 배송을 선택해 주세요." to R.string.request_error_parcels_unavailable,
            "캠핑장 사이트 번호 또는 예약 참조를 입력해 주세요." to R.string.camp_enter_a_pitch_or_reservation_reference,
            "체류 일정을 선택해 주세요." to R.string.request_error_stay_required,
            "올바른 체류 일정을 선택해 주세요." to R.string.request_error_stay_invalid,
            "예상 도착일이 체류 일정과 맞지 않습니다. 자택 배송이나 일정을 변경해 주세요." to R.string.request_error_delivery_dates,
            "일부 상품의 재고가 부족합니다. 수량을 변경해 주세요." to R.string.request_error_insufficient_stock,
        )

        // Exact app-facing DomainError messages in service.py and sql_store.py.
        private val businessReasons = quoteReasons + mapOf(
            "체크아웃은 체크인 다음 날 이후로 선택해 주세요." to R.string.camp_choose_a_check_out_date_after_check,
            "지난 날짜에는 캠핑 일정을 만들 수 없습니다." to R.string.request_error_past_dates,
            "최대 30박까지 선택해 주세요." to R.string.request_error_maximum_stay,
            "체크인과 체크아웃을 함께 선택해 주세요." to R.string.request_error_dates_required,
            "캠핑장을 찾을 수 없습니다. 데이터 공급자와 검색 조건을 확인해 주세요." to R.string.request_error_campsite_not_found,
            "지원하지 않는 시설 필터입니다." to R.string.request_error_facility_filter,
            "선택한 일정과 인원의 자리가 없습니다. 다른 장소나 날짜를 선택해 주세요." to R.string.request_error_no_availability,
            "캠핑장 좌표가 없어 경로를 미리 볼 수 없습니다." to R.string.request_error_route_coordinates,
            "내 캠핑에 목적지를 먼저 저장해 주세요." to R.string.camp_save_a_campsite_to_my_trip_first,
            "공조 목표 온도는 16–30°C로 선택해 주세요." to R.string.request_error_temperature_range,
            "이 데모 차량 공조는 전체 실내 구역을 지원합니다." to R.string.request_error_climate_zone,
            "앞좌석 또는 뒷좌석 구역을 선택해 주세요." to R.string.request_error_seat_zone,
            "독서등은 켜짐/꺼짐으로 선택해 주세요." to R.string.request_error_reading_light,
            "밝기·차광 값은 0–100 범위로 선택해 주세요." to R.string.request_error_percentage_range,
            "창문 상태 제어값을 확인해 주세요." to R.string.request_error_window_value,
            "상품을 찾을 수 없습니다." to R.string.request_error_product_not_found,
            "요청 수량이 재고를 초과했습니다." to R.string.request_error_stock_exceeded,
            "다른 요청이 재고나 저장 상태를 변경했습니다. 최신 상태에서 다시 확인해 주세요." to R.string.request_error_concurrent_change,
            "같은 주문 키에 다른 요청이 전달되었습니다. 새 주문으로 다시 확인해 주세요." to R.string.request_error_order_conflict,
            "같은 주문 키에 다른 요청이 전달되었습니다." to R.string.request_error_order_conflict,
            "주문을 찾을 수 없습니다." to R.string.request_error_order_not_found,
        )

        fun fromResponse(statusCode: Int, detail: String): CampRequestFailure =
            CampRequestFailure(statusCode, resourcesFor(detail))

        fun localizedReason(detail: String): String = localize(resourcesFor(detail))

        internal fun resourcesFor(detail: String): List<Int> {
            val text = detail.trim()
            businessReasons[text]?.let { return listOf(it) }
            var remaining = text
            val resources = mutableListOf<Int>()
            while (remaining.isNotEmpty()) {
                val reason = quoteReasons.entries.firstOrNull {
                    remaining == it.key || remaining.startsWith(it.key + " ")
                } ?: return listOf(R.string.camp_we_couldn_t_complete_the_request)
                resources += reason.value
                remaining = remaining.removePrefix(reason.key).trimStart()
            }
            return resources.ifEmpty { listOf(R.string.camp_we_couldn_t_complete_the_request) }
        }

        private fun localize(resources: List<Int>): String =
            resources.joinToString(" ") { CampText.get(it) }
    }
}
