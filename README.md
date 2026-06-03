# banquito-routing-service

Microservicio de **Motor de Enrutamiento** del Switch de Pagos Masivos BanQuito V2.  
Desarrollado por: **Paul Gualotuna — Grupo 1**

---

## ¿Qué hace este microservicio?

Recibe cada línea de pago publicada por el servicio de recepción de archivos (**Alan**), decide cómo enrutarla y ejecuta el procesamiento:

- **On-Us (código `001`)** → llama a Oscar vía gRPC para acreditar la cuenta destino
- **Off-Us (otros bancos válidos)** → publica en la cola `clearing.outbound.queue` para el sistema de compensación
- **Código inválido** → rechaza la línea con error `ROUTING_CODE_INVALID`

Cuando todas las líneas de un lote están procesadas, calcula la comisión (Johan) y la debita de la cuenta corporativa (Oscar).

---

## Arquitectura y flujo de datos

```
                    ┌─────────────────────────────────┐
                    │   Alan (file-reception-service)  │
                    │   POST /api/v1/payments/batches   │
                    │   Lee CSV → publica líneas        │
                    └──────────────┬──────────────────┘
                                   │ RabbitMQ
                                   │ payment.lines.queue
                                   ▼
                    ┌─────────────────────────────────┐
                    │   banquito-routing-service        │  ← ESTE SERVICIO
                    │   Puerto: 8085                    │
                    │   5-20 workers concurrentes       │
                    │   MongoDB: routingdb              │
                    └──┬──────────┬──────────┬────────┘
                       │ gRPC     │ gRPC     │ gRPC
                       ▼          ▼          ▼
               [Oscar:9090]  [Johan:9091]  [Anthony:9092]
               BatchCredit   Tariff        Notification
               CorporateDebit
```

**Comunicación:**
- **Entrada**: RabbitMQ (consume de Alan)
- **Salida On-Us**: gRPC → Oscar (account-core-service)
- **Salida Off-Us**: RabbitMQ → `clearing.outbound.queue`
- **Comisión**: gRPC → Johan (tariff-service) + Oscar (CorporateDebit)
- **Notificaciones**: gRPC fire-and-forget → Anthony (notification-service)
- **Estado del lote**: REST GET expuesto para que Anthony y otros consulten

---

## Puertos

| Servicio | Puerto | Descripción |
|---|---|---|
| banquito-routing-service | **8085** | REST API |
| banquito-mongo-routing | **27018** | MongoDB propio (separado del de Alan) |

---

## Requisitos previos para ejecutar

Este servicio **depende** de que el compose de Alan esté corriendo primero, porque usa su RabbitMQ.

### Servicios de Alan que se usan (no tocar):
| Contenedor | Puerto | Uso |
|---|---|---|
| `banquito-rabbitmq` | 5672 | Cola de entrada `payment.lines.queue` |
| `banquito-rabbitmq` | 15672 | Consola web (admin) |

---

## Levantar el servicio

### Paso 1 — Levantar el compose de Alan
```bash
cd ../banquito-file-reception-service
docker-compose up -d
```

Verificar que esté corriendo:
```bash
docker ps | grep banquito-rabbitmq
```

### Paso 2 — Levantar el routing-service
```bash
cd ../banquito-routing-service
docker-compose up --build -d
```

El `--build` es necesario la primera vez. Las siguientes veces basta con:
```bash
docker-compose up -d
```

### Paso 3 — Verificar que está activo
```bash
curl http://localhost:8085/actuator/health
```

Respuesta esperada: `"mongo": {"status": "UP"}, "rabbit": {"status": "UP"}`

---

## Endpoints REST

### GET `/api/v2/payments/batches/{batchId}/status`

Retorna el estado actual de un lote de pagos.

**Ejemplo de request:**
```
GET http://localhost:8085/api/v2/payments/batches/550e8400-e29b-41d4-a716-446655440000/status
```

**Respuesta exitosa (200):**
```json
{
  "batchId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "COMPLETED",
  "declaredTotalRecords": 100,
  "successfulRecords": 97,
  "rejectedRecords": 3,
  "successfulAmount": 145500.00,
  "rejectedAmount": 4500.00,
  "createdAt": "2026-06-03T10:00:00",
  "updatedAt": "2026-06-03T10:05:30",
  "completedAt": "2026-06-03T10:05:35"
}
```

**Respuesta cuando no existe (404):**
```json
(cuerpo vacío — batch no encontrado)
```

**Estados posibles del campo `status`:**
| Estado | Descripción |
|---|---|
| `PROCESSING` | Líneas siendo procesadas |
| `COMPLETING` | Calculando comisión y debitando |
| `COMPLETED` | Todo procesado exitosamente |
| `FAILED` | Error al completar (tarifa o débito fallaron) |

### GET `/actuator/health`
Health check completo del servicio. Verifica MongoDB y RabbitMQ.

---

## Colas RabbitMQ

### Cola que CONSUME (de Alan)
| Cola | Tipo | Descripción |
|---|---|---|
| `payment.lines.queue` | Durable | Recibe cada línea del CSV procesado |

**Formato del mensaje JSON que llega de Alan:**
```json
{
  "batchId": "uuid-del-lote",
  "lineNumber": 1,
  "routingCode": "001",
  "accountDestination": "2200012345",
  "amount": 1500.00,
  "reference": "Nómina Mayo 2026",
  "beneficiaryName": "María García",
  "beneficiaryEmail": "maria@example.com",
  "transactionUuid": "abc-123-def",
  "declaredTotalRecords": 100
}
```

> **Nota para Alan:** el campo `declaredTotalRecords` es necesario para que este servicio detecte cuándo se completa el lote y dispare el cobro de comisión. Debe enviarse en cada mensaje de la cola.

### Cola que PUBLICA (para compensación Off-Us)
| Cola | Tipo | Descripción |
|---|---|---|
| `clearing.outbound.queue` | Durable | Líneas destinadas a otros bancos |

---

## gRPC — Contratos con otros microservicios

Este servicio no expone gRPC propio. Solo **consume** gRPC de otros servicios.  
Los archivos `.proto` se encuentran en `src/main/proto/`.

### Contrato con Oscar (account-core-service — puerto 9090)

**Archivo:** `src/main/proto/account-core.proto`

```protobuf
rpc BatchCredit(BatchCreditRequest) returns (BatchCreditResponse)
rpc CorporateDebit(CorporateDebitRequest) returns (CorporateDebitResponse)
```

**Cuándo se llama:**
- `BatchCredit`: una vez por cada línea On-Us (routingCode = `001`)
- `CorporateDebit`: una vez al completarse todo el lote (cobro de comisión)

### Contrato con Johan (tariff-service — puerto 9091)

**Archivo:** `src/main/proto/tariff-service.proto`

```protobuf
rpc CalculateTariff(TariffRequest) returns (TariffResponse)
```

**Cuándo se llama:** una vez al detectar que el lote está completo (antes de CorporateDebit).

**Request que se envía:**
```json
{ "successfulTx": 97, "batchId": "uuid-del-lote" }
```

**Response esperada de Johan:**
```json
{
  "successfulTx": 97,
  "unitFee": 0.50,
  "commissionSubtotal": 48.50,
  "ivaRate": 0.15,
  "ivaAmount": 7.275,
  "totalCharge": 55.775,
  "tariffRangeApplied": "50-100"
}
```

### Contrato con Anthony (notification-service — puerto 9092)

**Archivo:** `src/main/proto/notification-service.proto`

```protobuf
rpc SendNotification(NotificationRequest) returns (NotificationResponse)
```

**Comportamiento:** fire-and-forget. Si Anthony no está disponible, el pago se procesa igual y se registra un warning en el log. No bloquea el flujo.

---

## Base de datos MongoDB

**Base de datos:** `routingdb`  
**Contenedor:** `banquito-mongo-routing`  
**Puerto:** 27018

### Colección `payment_batch`

Un documento por cada lote. Se crea al llegar la primera línea.

| Campo | Tipo | Descripción |
|---|---|---|
| `batchId` | String (único) | UUID del lote, viene de Alan |
| `status` | String | PROCESSING / COMPLETING / COMPLETED / FAILED |
| `declaredTotalRecords` | Int | Total de líneas declaradas en el CSV |
| `successfulRecords` | Int | Líneas procesadas exitosamente |
| `rejectedRecords` | Int | Líneas rechazadas |
| `successfulAmount` | Double | Suma de montos exitosos |
| `rejectedAmount` | Double | Suma de montos rechazados |
| `createdAt` | DateTime | Timestamp de la primera línea recibida |
| `updatedAt` | DateTime | Última actualización |
| `completedAt` | DateTime | Timestamp de completación del lote |

### Colección `payment_detail`

Un documento por cada línea de pago procesada.  
Índice único en `{batchId, lineNumber}` — garantiza idempotencia.

| Campo | Tipo | Descripción |
|---|---|---|
| `batchId` | String | UUID del lote |
| `lineNumber` | Int | Número de línea en el CSV |
| `transactionUuid` | String | UUID único de la transacción |
| `routingCode` | String | Código del banco destino |
| `accountDestination` | String | Número de cuenta del beneficiario |
| `amount` | Double | Monto de la transferencia |
| `reference` | String | Referencia del pago |
| `beneficiaryName` | String | Nombre del beneficiario |
| `beneficiaryEmail` | String | Email del beneficiario |
| `status` | String | PROCESSING / PROCESSED / CLEARED / REJECTED |
| `errorCode` | String | Código de error si fue rechazado |
| `errorMessage` | String | Descripción del error |
| `processedAt` | DateTime | Timestamp de procesamiento |

**Códigos de error posibles:**
| Código | Causa |
|---|---|
| `ROUTING_CODE_INVALID` | El código de banco no existe o es inválido |
| `ONUS_PROCESSING_ERROR` | Oscar rechazó el crédito |
| `OFFUS_ROUTING_ERROR` | Error al publicar en la cola de clearing |

---

## Variables de entorno

| Variable | Valor en Docker | Descripción |
|---|---|---|
| `SERVER_PORT` | 8085 | Puerto del servicio |
| `SPRING_DATA_MONGODB_URI` | `mongodb://mongo-routing:27017/routingdb` | URI MongoDB |
| `SPRING_MONGODB_URI` | `mongodb://mongo-routing:27017/routingdb` | URI MongoDB (Spring Boot 4) |
| `RABBITMQ_HOST` | `rabbitmq` | Host RabbitMQ de Alan |
| `RABBITMQ_PORT` | 5672 | Puerto AMQP |
| `RABBITMQ_USERNAME` | guest | Usuario RabbitMQ |
| `RABBITMQ_PASSWORD` | guest | Contraseña RabbitMQ |
| `RABBITMQ_QUEUE` | `payment.lines.queue` | Cola de entrada |
| `RABBITMQ_CLEARING_QUEUE` | `clearing.outbound.queue` | Cola de salida Off-Us |
| `ACCOUNT_CORE_GRPC_HOST` | `account-core-service` | Nombre contenedor de Oscar |
| `ACCOUNT_CORE_GRPC_PORT` | 9090 | Puerto gRPC de Oscar |
| `TARIFF_SERVICE_GRPC_HOST` | `tariff-service` | Nombre contenedor de Johan |
| `TARIFF_SERVICE_GRPC_PORT` | 9091 | Puerto gRPC de Johan |
| `NOTIFICATION_SERVICE_GRPC_HOST` | `notification-service` | Nombre contenedor de Anthony |
| `NOTIFICATION_SERVICE_GRPC_PORT` | 9092 | Puerto gRPC de Anthony |

---

## Idempotencia

Cada línea de pago se procesa **exactamente una vez**, garantizado por:

1. Índice único `{batchId, lineNumber}` en la colección `payment_detail`
2. Al llegar un mensaje duplicado, el insert falla con `DuplicateKeyException` y se descarta silenciosamente
3. Los 5-20 workers concurrentes no pueden procesar la misma línea dos veces

---

## Estructura del proyecto

```
banquito-routing-service/
├── docker-compose.yml
├── Dockerfile
├── pom.xml
└── src/main/
    ├── proto/
    │   ├── account-core.proto          # Contrato gRPC con Oscar
    │   ├── tariff-service.proto        # Contrato gRPC con Johan
    │   └── notification-service.proto  # Contrato gRPC con Anthony
    ├── resources/
    │   └── application.properties
    └── java/ec/edu/espe/banquito/routingservice/
        ├── config/RabbitMQConfig.java           # Colas y serialización JSON
        ├── client/
        │   ├── AccountCoreGrpcClient.java        # gRPC → Oscar
        │   ├── TariffGrpcClient.java             # gRPC → Johan
        │   └── NotificationGrpcClient.java       # gRPC → Anthony (fire-and-forget)
        ├── model/
        │   ├── PaymentBatch.java                 # Documento MongoDB del lote
        │   ├── PaymentDetail.java                # Documento MongoDB por línea
        │   └── PaymentLineMessage.java           # POJO del mensaje RabbitMQ
        ├── dto/
        │   └── BatchStatusResponse.java          # DTO del endpoint REST
        ├── repository/
        │   ├── PaymentBatchRepository.java
        │   └── PaymentDetailRepository.java
        ├── service/
        │   └── RoutingService.java               # Lógica principal (@RabbitListener)
        └── controller/
            └── RoutingController.java            # Endpoint REST de estado
```

---

## Logs y diagnóstico

**Ver logs en tiempo real:**
```bash
docker logs -f banquito-routing-service
```

**Conectarse a MongoDB:**
```bash
docker exec -it banquito-mongo-routing mongosh routingdb
```

**Consultas útiles en MongoDB:**
```javascript
// Ver todos los lotes
db.payment_batch.find().pretty()

// Ver detalles de un lote específico
db.payment_detail.find({ batchId: "uuid-del-lote" }).pretty()

// Contar líneas por estado
db.payment_detail.aggregate([
  { $group: { _id: "$status", total: { $sum: 1 } } }
])

// Ver lotes completados
db.payment_batch.find({ status: "COMPLETED" }).pretty()
```

**Consola web RabbitMQ** (usuario: guest / contraseña: guest):
```
http://localhost:15672
```

---

## Guía de integración para otros equipos

### Para Oscar (account-core-service)
- Exponer gRPC en puerto **9090** con nombre de contenedor `account-core-service`
- Implementar `BatchCredit` y `CorporateDebit` según `src/main/proto/account-core.proto`
- El campo `account_id` en `CreditInstruction` recibe los últimos 9 dígitos del número de cuenta destino

### Para Johan (tariff-service)
- Exponer gRPC en puerto **9091** con nombre de contenedor `tariff-service`
- Implementar `CalculateTariff` según `src/main/proto/tariff-service.proto`
- Se le pasa la cantidad de transacciones exitosas y el `batchId`

### Para Anthony (notification-service)
- Exponer gRPC en puerto **9092** con nombre de contenedor `notification-service`
- Implementar `SendNotification` según `src/main/proto/notification-service.proto`
- Las llamadas son fire-and-forget: si Anthony no responde, el pago no se revierte
- Puede consultar el estado de cualquier lote en:
  ```
  GET http://banquito-routing-service:8085/api/v2/payments/batches/{batchId}/status
  ```

### Para Anahy (infraestructura)
- Este servicio necesita estar en la **misma red Docker** que el RabbitMQ de Alan
- La red se llama `banquito-file-reception-service_default`
- MongoDB propio en puerto **27018** (Alan usa el 27017)
- Si Oscar, Johan o Anthony no están disponibles, las líneas se marcan como `REJECTED` pero el servicio **no crashea**

---

## Tecnologías

| Tecnología | Versión | Uso |
|---|---|---|
| Java | 21 | Lenguaje |
| Spring Boot | 4.0.6 | Framework principal |
| Spring Data MongoDB | 5.0.5 | Persistencia |
| Spring AMQP | — | Consumo de RabbitMQ |
| gRPC / Netty | 1.62.2 | Comunicación interna |
| MongoDB | 7 | Base de datos propia |
| RabbitMQ | 4 (de Alan) | Mensajería |
| Docker + Compose | — | Contenedores |
| Maven | 3.9 | Build y generación de código gRPC |
