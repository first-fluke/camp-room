package camp.room.app

/** Provider API region identifiers remain unchanged across app languages. */
object CampRegions {
    const val ALL = "전체"

    data class Option(val apiValue: String, val titleResource: Int)

    val options =
        listOf(
            Option(ALL, R.string.camp_all),
            Option("강원", R.string.camp_gangwon),
            Option("경기", R.string.camp_gyeonggi),
            Option("충북", R.string.camp_chungbuk),
            Option("경북", R.string.camp_gyeongbuk),
            Option("제주", R.string.camp_jeju),
        )

    fun label(value: String): String =
        options.firstOrNull { it.apiValue == value }?.let { CampText.get(it.titleResource) }
            ?: value
}
