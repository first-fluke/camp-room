# Camp Room admin contract

User added web administration and Turso remote persistence; native Android is still the primary user frontend.

Backend requires `Authorization: Bearer CAMP_ADMIN_TOKEN` on every /api/v1/admin route;503 ifnotconfigured,401 missing/wrong. Never exposeTurso/providercredentials.

- GET /api/v1/admin/overview -> {database:{backend,namespace},providers,campsites,orders,counts}.
- PATCH /api/v1/admin/campsites/{id}/receiving body {acceptsParcels,location,instructions} -> {campsiteId,delivery:{acceptsParcels,location,instructions,updatedAt,source:'admin'}}. Persistedoverride is used by realquote/ordervalidation.
- PATCH /api/v1/admin/orders/{id} body {fulfillmentStatus:'pending'|'packing'|'ready'|'cancelled',adminNote} -> order with unchangeddemo_confirmed paymentstatus, separatefulfillment:{status,adminNote,updatedAt,mode:'demo'}. No actual payment/carrier API implied.

Next apps/admin binds127.0.0.1:3000. Login/password CAMP_ADMIN_PASSWORD and signed8h HttpOnly/SameSite session using CAMP_ADMIN_SESSION_SECRET. BFF injects backend Bearer onlyserver-side and rejectscross-origin mutations. NoNEXT_PUBLIC secrets. Actual.env staysignored.
