from datetime import date
from contextlib import asynccontextmanager
import secrets
from fastapi import FastAPI, Query, Request, Depends, Header
from fastapi.responses import JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from .config import Settings
from .errors import DomainError
from .models import RefreshInput, ReceivingInput, FulfillmentInput, CartAdd, CartQuantity, CheckoutInput, ControlInput, NavigationInput, OrderInput, SceneInput, TripInput
from .service import CampService
from . import responses as dto


def create_app(settings: Settings | None = None) -> FastAPI:
    service = CampService(settings or Settings.from_env())
    @asynccontextmanager
    async def lifespan(app):
        service.ensure_catalog_refresh()
        try:
            yield
        finally:
            await service.stop_catalog_refresh()
            service.repo.close()

    application = FastAPI(lifespan=lifespan, title="Camp Room API", version="1.0.0", description="명시적 데모 여행/차박/주문과 서버 데이터 공급자 어댑터", responses={400: {"model": dto.ErrorResponse}, 401: {"model": dto.ErrorResponse}, 404: {"model": dto.ErrorResponse}, 409: {"model": dto.ErrorResponse}, 503: {"model": dto.ErrorResponse}})
    application.state.service = service
    application.add_middleware(CORSMiddleware, allow_origins=["http://127.0.0.1:3000", "http://localhost:3000"], allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE"], allow_headers=["Content-Type"])

    @application.exception_handler(DomainError)
    async def domain_error(request: Request, exc: DomainError):
        return JSONResponse(status_code=exc.status, content={"detail": exc.detail})

    def admin_access(authorization: str | None = Header(default=None)):
        token = service.settings.admin_token
        if not token:
            raise DomainError("운영 관리자 인증이 설정되지 않았습니다.", 503)
        expected = "Bearer " + token
        if not authorization or not secrets.compare_digest(authorization.encode(), expected.encode()):
            raise DomainError("관리자 인증이 필요합니다.", 401)

    @application.get("/api/v1/admin/overview", response_model=dto.AdminOverview, response_model_exclude_unset=True, operation_id="getAdminOverview", tags=["Admin"], dependencies=[Depends(admin_access)])
    async def admin_overview():
        return await service.admin_overview()

    @application.post("/api/v1/admin/providers/refresh", response_model=dto.ProviderRefresh, operation_id="refreshProviders", tags=["Admin"], dependencies=[Depends(admin_access)])
    async def refresh_providers(data: RefreshInput):
        return await service.refresh_providers(data.force)

    @application.patch("/api/v1/admin/campsites/{site_id}/receiving", response_model=dto.ReceivingUpdate, response_model_exclude_unset=True, operation_id="updateReceivingPolicy", tags=["Admin"], dependencies=[Depends(admin_access)])
    async def admin_receiving(site_id: str, data: ReceivingInput):
        return await service.admin_receiving(site_id, data)

    @application.patch("/api/v1/admin/orders/{order_id}", response_model=dto.Order, response_model_exclude_unset=True, operation_id="updateFulfillment", tags=["Admin"], dependencies=[Depends(admin_access)])
    def admin_order(order_id: str, data: FulfillmentInput):
        return service.admin_fulfillment(order_id, data)

    @application.get("/health", response_model=dto.Health, response_model_exclude_unset=True, operation_id="getHealth")
    async def health():
        return {"status": "ok", "service": "camp-room", "dataMode": service.settings.mode, "database": service.repo.backend}

    @application.get("/api/v1/bootstrap", response_model=dto.Bootstrap, response_model_exclude_unset=True, operation_id="getBootstrap")
    async def bootstrap():
        return await service.bootstrap()

    @application.get("/api/v1/campsites", response_model=dto.CampsiteSearch, response_model_exclude_unset=True, operation_id="searchCampsites")
    async def campsites(region: str = Query("", max_length=80), checkIn: date | None = None, checkOut: date | None = None, guests: int = Query(2, ge=1, le=8), carCamping: bool | None = None, facilities: str = Query("", max_length=150), cursor: str | None = Query(None, max_length=2048), numOfRows: int = Query(20, ge=1, le=100)):
        return await service.search(region, checkIn, checkOut, guests, carCamping, facilities, cursor, numOfRows)

    @application.get("/api/v1/campsites/{site_id}", response_model=dto.Campsite, response_model_exclude_unset=True, operation_id="getCampsite")
    async def campsite(site_id: str):
        return await service.campsite(site_id)

    @application.post("/api/v1/availability/query", response_model=dto.Availability, response_model_exclude_unset=True, operation_id="queryAvailability")
    async def availability(data: TripInput):
        return await service.availability(data)

    @application.get("/api/v1/trip", response_model=dto.Trip | None, response_model_exclude_unset=True, operation_id="getTrip")
    def get_trip():
        return service.repo.read()["trip"]

    @application.put("/api/v1/trip", response_model=dto.Trip, response_model_exclude_unset=True, operation_id="saveTrip")
    async def save_trip(data: TripInput):
        return await service.save_trip(data)

    @application.get("/api/v1/weather", response_model=dto.Weather, response_model_exclude_unset=True, operation_id="getWeather")
    async def weather(campsiteId: str | None = None, checkIn: date | None = None, checkOut: date | None = None):
        return await service.weather(campsiteId, checkIn, checkOut)

    @application.post("/api/v1/navigation/preview", response_model=dto.NavigationPreview, response_model_exclude_unset=True, operation_id="previewNavigation")
    async def navigation_preview(data: NavigationInput):
        return await service.navigation(data.campsiteId)

    @application.post("/api/v1/navigation/start", response_model=dto.NavigationStart, response_model_exclude_unset=True, operation_id="startNavigation")
    async def navigation_start(data: NavigationInput):
        return await service.navigation(data.campsiteId, start=True)

    @application.get("/api/v1/vehicle", response_model=dto.Vehicle, response_model_exclude_unset=True, operation_id="getVehicle")
    def vehicle():
        return service.repo.read()["vehicle"]

    @application.post("/api/v1/vehicle/scenes", response_model=dto.VehicleAction, response_model_exclude_unset=True, operation_id="applyScene")
    def scenes(data: SceneInput):
        return service.scene(data.sceneId, data.failControl)

    @application.post("/api/v1/vehicle/controls", response_model=dto.VehicleAction, response_model_exclude_unset=True, operation_id="controlVehicle")
    def controls(data: ControlInput):
        return service.control(data)

    @application.get("/api/v1/products", response_model=dto.ProductList, response_model_exclude_unset=True, operation_id="listProducts")
    def product_list(campsiteId: str | None = None):
        return {"items": service.product_list(service.repo.read())}

    @application.get("/api/v1/cart", response_model=dto.Cart, response_model_exclude_unset=True, operation_id="getCart")
    def cart():
        return service.cart_view(service.repo.read())

    @application.post("/api/v1/cart/items", response_model=dto.Cart, response_model_exclude_unset=True, operation_id="addCartItem")
    def cart_add(data: CartAdd):
        return service.change_cart(data.productId, data.quantity, add=True)

    @application.patch("/api/v1/cart/items/{product_id}", response_model=dto.Cart, response_model_exclude_unset=True, operation_id="updateCartItem")
    def cart_change(product_id: str, data: CartQuantity):
        return service.change_cart(product_id, data.quantity)

    @application.delete("/api/v1/cart/items/{product_id}", response_model=dto.Cart, response_model_exclude_unset=True, operation_id="deleteCartItem")
    def cart_delete(product_id: str):
        return service.change_cart(product_id, delete=True)

    @application.post("/api/v1/checkout/quote", response_model=dto.CheckoutQuote, response_model_exclude_unset=True, operation_id="quoteCheckout")
    async def quote(data: CheckoutInput):
        return await service.quote(data)

    @application.post("/api/v1/orders", response_model=dto.Order, response_model_exclude_unset=True, operation_id="createOrder")
    async def create_order(data: OrderInput):
        return await service.create_order(data)

    @application.get("/api/v1/orders", response_model=dto.OrderList, response_model_exclude_unset=True, operation_id="listOrders")
    def orders():
        return {"items": service.repo.read()["orders"]}

    return application

app = create_app()
