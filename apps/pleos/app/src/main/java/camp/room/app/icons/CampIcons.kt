package camp.room.app.icons

/** Official Pleos controls first. Phosphor fills camping/category gaps; provenance is in assets. */
object CampIcons {
    val ChevronRight get() = CampPleos.ChevronRight
    val ChevronDown get() = CampPleos.ChevronDown
    val Close get() = CampPleos.Close
    val Microphone get() = CampPleos.Microphone
    val Speaker get() = CampPleos.Speaker
    val WarningCircle get() = CampPleos.WarningCircle
    val CheckCircle get() = CampPleos.CheckCircle
    val Check get() = CampPleos.Check
    val Eye get() = CampPleos.Eye
    val Plus get() = CampPleos.Plus
    val Minus get() = CampPleos.Minus
    val Trash get() = CampPleos.Trash
    val Lightbulb get() = CampPleos.Lightbulb
    val BatteryCharging get() = CampPleos.BatteryCharging
    val Vehicle get() = CampPleos.Vehicle
    val Clock get() = CampPleos.Clock
    val Refresh get() = CampPleos.Refresh
    val Search get() = CampPleos.Search
    val Wind get() = CampPleos.Wind
    val Compass get() = CampPhosphor.Compass
    val Tent get() = CampPhosphor.Tent
    val TreeEvergreen get() = CampPhosphor.TreeEvergreen
    val CloudSlash get() = CampPhosphor.CloudSlash
    val Globe get() = CampPhosphor.Globe
    val ShoppingBag get() = CampPhosphor.ShoppingBag
    val MapPin get() = CampPhosphor.MapPin
    val Crosshair get() = CampPhosphor.CrosshairSimple
    val SlidersHorizontal get() = CampPhosphor.SlidersHorizontal
    val Shower get() = CampPhosphor.Shower
    val Lightning get() = CampPhosphor.Lightning
    val PawPrint get() = CampPhosphor.PawPrint
    val Users get() = CampPhosphor.Users
    val Calendar get() = CampPhosphor.CalendarBlank
    val ArrowUpRight get() = CampPhosphor.ArrowUpRight
    val Pencil get() = CampPhosphor.PencilSimple
    val BellRinging get() = CampPhosphor.BellRinging
    val Sun get() = CampPhosphor.Sun
    val NavigationArrow get() = CampPhosphor.NavigationArrow
    val House get() = CampPhosphor.House
    val Truck get() = CampPhosphor.Truck
    val Leaf get() = CampPhosphor.Leaf
    val Door get() = CampPhosphor.Door
    val Thermometer get() = CampPhosphor.ThermometerSimple

    object Selected {
        val Compass get() = CampPhosphor.Fill.Compass
        val Tent get() = CampPhosphor.Fill.Tent

        // Official vehicle geometry is already solid; selection changes tint, not its provenance.
        val Vehicle get() = CampPleos.Vehicle
    }

    object Artwork {
        val TreeEvergreen get() = CampPhosphor.Duotone.TreeEvergreen
        val Mountains get() = CampPhosphor.Duotone.Mountains
        val Sun get() = CampPhosphor.Duotone.Sun
        val MoonStars get() = CampPhosphor.Duotone.MoonStars
        val Package get() = CampPhosphor.Duotone.Package
        val BookOpen get() = CampPhosphor.Duotone.BookOpen
        val Backpack get() = CampPhosphor.Duotone.Backpack
        val Armchair get() = CampPhosphor.Duotone.Armchair
        val Coffee get() = CampPhosphor.Duotone.Coffee
        val Flashlight get() = CampPhosphor.Duotone.Flashlight
        val Bed get() = CampPhosphor.Duotone.Bed
    }
}
