package camp.room.app

/** Covers the gap between a provider call and publication in the host's owned resource set. */
internal class SdkResourceConstruction(private val lock: Any) {
    private class Raw(val identity: Any, val release: () -> Unit) { var inFlight=false }
    private var constructing=0
    private val abandoned=mutableListOf<Raw>()

    /** adopt only inserts the completed wrapper into the host's metadata-owned set. */
    fun <E:Any,R> create(admit:()->Boolean={true}, factory:()->E, construct:(E)->R, releaseUnwrapped:(E)->Unit, adopt:(R)->Unit):R {
        synchronized(lock){check(admit()){ "SDK resource host is inactive" };constructing++}
        var engine:E?=null
        var settled=false
        try {
            val created=factory().also{engine=it}
            val wrapped=construct(created)
            synchronized(lock) {
                adopt(wrapped)
                constructing--;settled=true
            }
            return wrapped
        } finally {
            if(!settled)synchronized(lock) {
                // A provider that throws without returning a handle cannot be
                // released by its caller. A returned handle is retained exactly.
                engine?.let { created ->
                    if(abandoned.none{it.identity===created})abandoned+=Raw(created){releaseUnwrapped(created)}
                }
                constructing--
            }
        }
    }

    fun hasOutstanding():Boolean=synchronized(lock){constructing>0 || abandoned.isNotEmpty()}
    fun hasAbandoned():Boolean=synchronized(lock){abandoned.isNotEmpty()}

    /** Native release is never run while holding the publication/callback monitor. */
    fun drainAbandoned(onFailure:(Exception)->Unit={}) {
        val due=synchronized(lock){abandoned.filter{!it.inFlight}.onEach{it.inFlight=true}}
        due.forEach { entry ->
            try {entry.release();synchronized(lock){abandoned.remove(entry)}}
            catch(error:Exception) {
                synchronized(lock){entry.inFlight=false}
                runCatching{onFailure(error)}
            }
        }
    }
}
