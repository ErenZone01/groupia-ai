# Operational Architecture Rules

These rules complement the existing Groupia architecture rules and remove ambiguity around authorization, transactions, events, payments, money and module verification.

## Authorization and organization isolation

Organization membership alone is not sufficient authorization.

Every authenticated organization action must validate:

1. the authenticated user;
2. membership in the target organization;
3. the permission required by the operation.

Organization roles are:

* OWNER
* MANAGER
* OPERATOR
* VIEWER

The MVP may initially create only OWNER memberships, but authorization code must be designed around explicit permissions rather than simple membership checks.

Typical responsibilities:

OWNER:

* full organization access;
* organization settings;
* member management;
* destructive administrative actions.

MANAGER:

* manage campaigns;
* manage catalog;
* manage orders;
* manage shipping;
* view payments.

OPERATOR:

* process orders;
* record weights;
* mark pickup or distribution states;
* perform operational tasks explicitly granted by policy.

VIEWER:

* read-only organization access.

Do not hardcode role comparisons throughout controllers.

Prefer a centralized authorization component or permission policy.

## Public storefront security

Public storefront endpoints are intentionally public only for information intended to be public.

A public campaign identifier may expose:

* campaign title;
* campaign description;
* public products;
* variants;
* prices;
* campaign dates;
* public shipping estimates or rules.

It must never expose:

* private customer information;
* internal payment information;
* organizer administrative data;
* private order information;
* internal audit information.

The frontend must never decide which organization owns a public request.

For public campaign operations, the backend must resolve:

public campaign identifier
→ campaign
→ organization

The client must not provide `organizationId` as a trusted value.

For example, a public checkout request may reference a public campaign and offers, but the server derives the owning organization itself.

An unpredictable UUID is not authorization.

Private customer order tracking requires an explicit access mechanism such as:

* a cryptographically random tracking token;
* authenticated customer access;
* or phone OTP verification.

## Transaction boundaries

Changes to one aggregate inside one module should normally happen inside one database transaction.

Cross-module workflows must not rely on long chains of synchronous service calls.

Use domain/application events for cross-module side effects when eventual consistency is acceptable.

Examples:

PaymentSucceeded
→ Ordering updates order state
→ Notification prepares confirmation

GroupingArrived
→ Ordering evaluates affected orders
→ Notification informs customers

The originating business transaction must commit independently from unrelated downstream side effects unless the business rule explicitly requires atomic consistency.

## Reliable application events

Cross-module transactional events must use Spring Modulith's persistent event publication mechanism.

Events that represent important business facts must include:

* `eventId`;
* `occurredAt`;
* the minimum identifiers necessary for consumers;
* no unnecessary sensitive data.

Consumers must be idempotent.

Processing the same event more than once must produce the same final business state as processing it once.

A consumer must never create duplicate:

* payments;
* notifications where duplication is harmful;
* order state transitions;
* financial adjustments;
* shipping charges.

Failed event publications must remain recoverable.

The application must have a documented strategy for:

* finding failed or incomplete publications;
* resubmitting them;
* monitoring repeated failures;
* preventing poison events from retrying indefinitely without visibility.

Do not introduce Kafka or RabbitMQ only to solve this problem during the MVP.

## Payment verification

A valid provider signature does not by itself prove that an order is correctly paid.

Before marking a Payment as `SUCCEEDED`, verify all applicable information:

* internal merchant reference;
* expected provider;
* provider transaction identifier;
* payment status;
* amount;
* currency;
* target order;
* payment purpose.

The received amount must match the amount expected by Groupia.

The received currency must match the expected currency.

Unknown or inconsistent payments must not modify the order automatically.

They must be recorded safely and surfaced for investigation.

## Payment state monotonicity

Out-of-order provider events must never regress a confirmed payment.

For example:

`PENDING → SUCCEEDED`

is valid.

After `SUCCEEDED`, a later duplicated or delayed `PENDING` or `FAILED` event must not change the payment back.

Refunds must use an explicit refund flow.

Do not reinterpret a successful payment as failed because of an older webhook.

Provider event identifiers must be stored and protected by a uniqueness constraint when the provider supplies such identifiers.

Webhook processing must be idempotent.

## Money and rounding

Never use `double` or `float`.

Use `BigDecimal`.

All monetary values must have an explicit currency.

Groupia currently uses XOF by default.

XOF has no fractional monetary unit in normal application charging, therefore final payable XOF amounts use scale `0`.

Use:

`RoundingMode.HALF_UP`

for Groupia business calculations unless a provider contract explicitly requires another rule.

Do not round weights before calculating shipping.

Recommended internal precision:

* kilograms: up to 3 decimal places or more when supplied by the source;
* cubic meters: up to 6 decimal places;
* monetary intermediate calculations: retain sufficient precision.

Round only when producing the final monetary charge for a calculation.

Example:

actualWeight × ratePerKg
→ precise intermediate result
→ final shipping charge rounded according to currency rules

Never repeatedly round intermediate values.

All payment amounts sent to payment providers must already be normalized according to the target currency.

## Historical financial values

Once an order is placed, its commercial values become historical facts.

Store snapshots for:

* unit price;
* quantity;
* product description necessary for the order;
* variant description;
* platform fee;
* estimated shipping amount;
* applicable shipping rate when relevant.

Changing catalog prices or platform configuration must not alter old orders.

When actual shipping becomes known, preserve both:

* estimated shipping;
* actual shipping.

Never overwrite the historical estimate.

## Module structure

Business modules follow this structure unless a specific module has a documented reason to differ:

```text
sn.groupia.<module>
│
├── api
│   ├── dto
│   └── event
│
├── application
│   ├── command
│   ├── query
│   └── service
│
├── domain
│   ├── model
│   ├── repository
│   ├── service
│   └── exception
│
└── infrastructure
    ├── persistence
    └── web
```

The module's `api` package is its explicit public interface.

Internal domain, application and infrastructure classes must not become accidental public APIs for other modules.

Use Spring Modulith named interfaces where appropriate to explicitly expose the module API.

Other modules must depend only on the exposed module interface.

## Cross-module dependencies

Never use another module's:

* repository;
* persistence entity;
* infrastructure implementation;
* internal service.

Use:

* an exposed API/facade;
* an exposed DTO;
* or an application event.

Cross-module references stored in persistence must use identifiers instead of JPA entity associations.

Example:

Correct:

```java
private UUID campaignId;
```

Incorrect:

```java
@ManyToOne
private GroupingCampaign campaign;
```

when `GroupingCampaign` belongs to another module.

## Automated architecture verification

Groupia must contain a Spring Modulith architecture test.

At minimum:

```java
class ModularityTests {

    @Test
    void verifiesModuleBoundaries() {
        ApplicationModules.of(GroupiaApplication.class).verify();
    }
}
```

This test must remain part of the normal test suite.

A feature that makes this test fail is not complete.

Architecture violations must be corrected rather than excluding modules from verification without a documented architectural decision.

## Build and verification commands

Before considering backend work complete, Codex must run the project's available verification commands.

On Windows PowerShell:

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
```

On Unix-like systems:

```bash
./mvnw test
./mvnw verify
```

For local application startup:

```powershell
.\mvnw.cmd spring-boot:run
```

If Docker-backed dependencies are required, document and use the repository's Docker Compose command before application startup.

Do not claim a task is complete if required tests were not run.

If a test cannot be run, explicitly state:

* which test was not run;
* why;
* what remains unverified.

## Definition of done extension

In addition to the existing definition of done, any feature involving multiple modules must verify:

* module boundaries;
* authorization;
* transaction boundaries;
* event failure behavior;
* consumer idempotence where relevant.

Any payment feature must additionally verify:

* duplicate webhooks;
* mismatched amount;
* mismatched currency;
* unknown reference;
* delayed webhook;
* out-of-order webhook;
* already-successful payment;
* provider failure.

Any shipping calculation feature must additionally verify:

* decimal weights;
* rounding;
* estimated versus actual values;
* zero and invalid weights;
* currency handling;
* historical preservation.
