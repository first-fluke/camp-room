package camp.room.app

data class CatalogPage<T>(
    val items: List<T>,
    val total: Int,
    val sourceTotal: Int,
    val nextCursor: String?,
    val generation: String,
    val warning: String = "",
)

class CatalogChangedException : Exception()

/** A search owns its own generation; responses from previous searches cannot publish. */
internal class CatalogPageHistory<T>(private val identity: (T) -> String) {
    var epoch: Long = 0
        private set
    private val pages = mutableListOf<CatalogPage<T>>()
    var index: Int = -1
        private set
    fun reset(): Long {
        epoch += 1
        pages.clear()
        index = -1
        return epoch
    }
    fun page(number: Int): CatalogPage<T>? = pages.getOrNull(number)
    fun select(number: Int): CatalogPage<T>? = page(number)?.also { index = number }
    fun accept(owner: Long, number: Int, value: CatalogPage<T>): Boolean {
        if (owner != epoch || number !in 0..pages.size) return false
        if (pages.isNotEmpty() && pages.first().generation != value.generation) return false
        val previousIds = pages.take(number).flatMap { it.items }.map(identity).toSet()
        val clean = value.copy(items = value.items.distinctBy(identity).filterNot { identity(it) in previousIds })
        if (number == pages.size) pages.add(clean) else pages[number] = clean
        index = number
        return true
    }
}
