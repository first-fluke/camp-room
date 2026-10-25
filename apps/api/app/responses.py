"""Public response contracts; the committed OpenAPI document drives native clients."""
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictFloat, StrictInt


class Response(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ProviderStatus(Response):
    name: str
    configured: bool
    status: str
    mode: Literal["demo", "live"]
    message: str


class SourceInfo(Response):
    name: str
    updatedAt: str
    mode: Literal["demo", "live"]
    providerModifiedAt: str | None = None
    coverage: str | None = None
    fallbackReason: str | None = None


class ReceivingPolicy(Response):
    acceptsParcels: bool
    location: str
    instructions: str
    updatedAt: str | None = None
    source: Literal["provider", "admin"] | None = None


class Availability(Response):
    status: Literal["available", "limited", "unavailable", "unknown"]
    remaining: int | None
    coverage: str
    checkedAt: str
    provider: str
    demo: bool
    checkIn: str | None = None
    checkOut: str | None = None
    guests: int | None = None


class Campsite(Response):
    id: str
    name: str
    region: str
    address: str
    latitude: float | None
    longitude: float | None
    nightlyPrice: int | None
    facilities: list[str]
    carCampingAllowed: bool | None
    evChargingAllowed: bool | None
    delivery: ReceivingPolicy
    description: str
    imageUrl: str
    checkInTime: str
    checkOutTime: str
    rules: list[str]
    availability: Availability
    source: SourceInfo


class CampsiteSearch(Response):
    items: list[Campsite]
    total: int
    source: ProviderStatus
    mode: Literal["demo", "live"]


class Trip(Response):
    campsiteId: str
    checkIn: str
    checkOut: str
    guests: int
    navigationStarted: bool
    updatedAt: str


class WeatherGrid(Response):
    nx: int
    ny: int


class WeatherForecast(Response):
    time: str
    temperature: float
    condition: str
    rainProbability: float | None


class Weather(Response):
    source: str
    updatedAt: str
    summary: str
    overnightLow: float | None
    rainProbability: float | None
    forecast: list[WeatherForecast]
    demo: bool
    status: Literal["available", "unavailable"]
    message: str | None = None
    issuedAt: str | None = None
    grid: WeatherGrid | None = None


class VehicleZone(Response):
    readingLight: bool
    ambientBrightness: float
    blindLevel: float


class Vehicle(Response):
    mode: Literal["demo"]
    motionState: Literal["parked", "moving", "unknown"]
    soc: float | None
    cabinTemperature: float | None
    targetTemperature: float | None
    front: VehicleZone
    rear: VehicleZone
    doorsClosed: bool | None
    windowsClosed: bool | None
    lastSceneId: str | None
    updatedAt: str


class ControlResult(Response):
    control: str
    target: StrictBool | StrictInt | StrictFloat
    status: Literal["applied", "failed", "blocked", "unsupported"]
    observed: StrictBool | StrictInt | StrictFloat | None
    confirmation: Literal["observed", "unverified"]
    message: str


class VehicleAction(Response):
    vehicle: Vehicle
    results: list[ControlResult]
    status: Literal["completed", "blocked", "partial"]


class NavigationPreview(Response):
    distanceKm: float
    durationMinutes: int
    consumptionKwh: float
    arrivalSoc: int
    chargingStop: bool
    mode: Literal["demo"]
    message: str


class NavigationStart(Response):
    started: bool
    mode: Literal["demo"]
    message: str
    trip: Trip
    preview: NavigationPreview


class Product(Response):
    id: str
    name: str
    price: int
    stock: int
    category: str
    recommendationReason: str
    imageUrl: str


class ProductList(Response):
    items: list[Product]


class CartLine(Response):
    productId: str
    name: str
    quantity: int
    unitPrice: int
    lineTotal: int
    imageUrl: str


class Cart(Response):
    items: list[CartLine]
    subtotal: int
    shipping: int
    total: int


class CheckoutQuote(Response):
    eligible: bool
    reason: str | None
    destination: Literal["home", "campsite"]
    eta: str
    receivingConditions: list[str]
    subtotal: int
    shipping: int
    total: int
    recipientName: str
    address: str
    siteReference: str
    phone: str | None
    mode: Literal["demo"]


class Fulfillment(Response):
    status: Literal["pending", "packing", "ready", "cancelled"]
    adminNote: str
    updatedAt: str
    mode: Literal["demo"]


class Order(Response):
    id: str
    status: Literal["demo_confirmed"]
    mode: Literal["demo"]
    createdAt: str
    items: list[CartLine]
    total: int
    subtotal: int
    shipping: int
    destination: Literal["home", "campsite"]
    eta: str
    receivingConditions: list[str]
    recipientName: str
    address: str
    siteReference: str
    phone: str | None
    campsiteId: str | None
    checkIn: str | None
    checkOut: str | None
    fulfillment: Fulfillment | None = None


class OrderList(Response):
    items: list[Order]


class Bootstrap(Response):
    mode: Literal["demo", "mixed"]
    campsites: list[Campsite]
    trip: Trip | None
    weather: Weather
    vehicle: Vehicle
    products: list[Product]
    cart: Cart
    orders: list[Order]
    providers: list[ProviderStatus]


class DatabaseStatus(Response):
    backend: str
    namespace: str


class AdminCounts(Response):
    campsites: int
    orders: int
    openOrders: int


class AdminOverview(Response):
    database: DatabaseStatus
    providers: list[ProviderStatus]
    campsites: list[Campsite]
    orders: list[Order]
    counts: AdminCounts


class ReceivingUpdate(Response):
    campsiteId: str
    delivery: ReceivingPolicy


class ProviderRefresh(Response):
    status: Literal["refreshed", "cached", "busy"]
    refreshedAt: str
    providers: list[ProviderStatus]
    campsites: int
    weatherQueries: int
    database: DatabaseStatus


class Health(Response):
    status: Literal["ok"]
    service: Literal["camp-room"]
    dataMode: Literal["demo", "auto", "live"]
    database: str


class ErrorResponse(Response):
    detail: str
