from datetime import date
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt, StrictFloat, model_validator

class Input(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

class TripInput(Input):
    campsiteId: str = Field(min_length=1, max_length=100)
    checkIn: date
    checkOut: date
    guests: int = Field(ge=1, le=8, strict=True)

    @model_validator(mode="after")
    def dates(self):
        if self.checkOut <= self.checkIn:
            raise ValueError("체크아웃은 체크인 다음 날 이후로 선택해 주세요.")
        if (self.checkOut - self.checkIn).days > 30:
            raise ValueError("한 번에 최대 30박까지 선택할 수 있습니다.")
        return self

class NavigationInput(Input):
    campsiteId: str = Field(min_length=1, max_length=100)

class SceneInput(Input):
    sceneId: Literal["load", "together", "split", "sleep", "departure"]
    failControl: str | None = Field(default=None, max_length=100)

class ControlInput(Input):
    control: str = Field(min_length=1, max_length=100)
    value: StrictBool | StrictInt | StrictFloat
    zone: Literal["front", "rear"] | None = None

class CartAdd(Input):
    productId: str = Field(min_length=1, max_length=100)
    quantity: int = Field(ge=1, le=99, strict=True)

class CartQuantity(Input):
    quantity: int = Field(ge=1, le=99, strict=True)

class CheckoutInput(Input):
    destination: Literal["home", "campsite"]
    campsiteId: str | None = Field(default=None, max_length=100)
    checkIn: date | None = None
    checkOut: date | None = None
    recipientName: str = Field(default="", max_length=80)
    address: str = Field(default="", max_length=300)
    siteReference: str = Field(default="", max_length=120)
    phone: str | None = Field(default=None, max_length=30)

class OrderInput(CheckoutInput):
    idempotencyKey: str = Field(min_length=8, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")


class ReceivingInput(Input):
    acceptsParcels: StrictBool
    location: str = Field(default="", max_length=200)
    instructions: str = Field(default="", max_length=1000)

    @model_validator(mode="after")
    def receiving_location(self):
        if self.acceptsParcels and not self.location:
            raise ValueError("택배 수령을 허용하려면 수령 장소를 입력해 주세요.")
        return self


class FulfillmentInput(Input):
    fulfillmentStatus: Literal["pending", "packing", "ready", "cancelled"]
    adminNote: str = Field(default="", max_length=1000)


class RefreshInput(Input):
    force: StrictBool = False
