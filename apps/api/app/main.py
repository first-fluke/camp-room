from datetime import date
from fastapi import FastAPI, Query, Request
from fastapi.responses import JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from .config import Settings
from .errors import DomainError
from .models import CartAdd, CartQuantity, CheckoutInput, ControlInput, NavigationInput, OrderInput, SceneInput, TripInput
from .service import CampService


def create_app(settings: Settings | None = None) -> FastAPI:
    service = CampService(settings or Settings.from_env())
    application = FastAPI(title="Camp Room API", version="1.0.0", description="명시적 데모 여행/차박/주문과 서버 데이터 공급자 어댑터")
    application.state.service = service
    application.add_middleware(CORSMiddleware, allow_origins=["http://127.0.0.1:3000", "http://localhost:3000"], allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE"], allow_headers=["Content-Type"])

    @application.exception_handler(DomainError)
    async def domain_error(request: Request, exc: DomainError):
        return JSONResponse(status_code=exc.status, content={"detail": exc.detail})

    @application.get("/health")
    async def health():
        return {"status": "ok", "service": "camp-room", "dataMode": service.settings.mode}

    @application.get("/api/v1/bootstrap")
    async def bootstrap():
        return await service.bootstrap()

    @application.get("/api/v1/campsites")
    async def campsites(region: str = Query("", max_length=80), checkIn: date | None = None, checkOut: date | None = None, guests: int = Query(2, ge=1, le=8), carCamping: bool | None = None, facilities: str = Query("", max_length=150)):
        return await service.search(region, checkIn, checkOut, guests, carCamping, facilities)

    @application.get("/api/v1/campsites/{site_id}")
    async def campsite(site_id: str):
        return await service.campsite(site_id)

    @application.post("/api/v1/availability/query")
    async def availability(data: TripInput):
        return await service.availability(data)

    @application.get("/api/v1/trip")
    async def get_trip():
        return service.repo.read()["trip"]

    @application.put("/api/v1/trip")
    async def save_trip(data: TripInput):
        return await service.save_trip(data)

    @application.get("/api/v1/weather")
    async def weather(campsiteId: str | None = None, checkIn: date | None = None, checkOut: date | None = None):
        return await service.weather(campsiteId, checkIn, checkOut)

    @application.post("/api/v1/navigation/preview")
    async def navigation_preview(data: NavigationInput):
        return await service.navigation(data.campsiteId)

    @application.post("/api/v1/navigation/start")
    async def navigation_start(data: NavigationInput):
        return await service.navigation(data.campsiteId, start=True)

    @application.get("/api/v1/vehicle")
    async def vehicle():
        return service.repo.read()["vehicle"]

    @application.post("/api/v1/vehicle/scenes")
    async def scenes(data: SceneInput):
        return service.scene(data.sceneId, data.failControl)

    @application.post("/api/v1/vehicle/controls")
    async def controls(data: ControlInput):
        return service.control(data)

    @application.get("/api/v1/products")
    async def product_list(campsiteId: str | None = None):
        return {"items": service.product_list(service.repo.read())}

    @application.get("/api/v1/cart")
    async def cart():
        return service.cart_view(service.repo.read())

    @application.post("/api/v1/cart/items")
    async def cart_add(data: CartAdd):
        return service.change_cart(data.productId, data.quantity, add=True)

    @application.patch("/api/v1/cart/items/{product_id}")
    async def cart_change(product_id: str, data: CartQuantity):
        return service.change_cart(product_id, data.quantity)

    @application.delete("/api/v1/cart/items/{product_id}")
    async def cart_delete(product_id: str):
        return service.change_cart(product_id, delete=True)

    @application.post("/api/v1/checkout/quote")
    async def quote(data: CheckoutInput):
        return await service.quote(data)

    @application.post("/api/v1/orders")
    async def create_order(data: OrderInput):
        return await service.create_order(data)

    @application.get("/api/v1/orders")
    async def orders():
        return {"items": service.repo.read()["orders"]}

    return application

app = create_app()
