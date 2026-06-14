# MVC Restructure Design

## Goal

Refactor the user service into a traditional Spring MVC-style package structure while preserving existing REST, gRPC, and service behavior.

## Scope

This change restructures Java packages and updates tests that enforce package boundaries. It does not add business features, change endpoint paths, change response envelopes, or change gRPC contracts.

## Target Package Layout

The service will use these top-level packages under `com.dating.user`:

- `controller`: REST controllers plus request DTOs and response VOs.
- `service`: business orchestration, validation, and application use cases.
- `model`: user-related data models currently represented by entity classes.
- `repository`: persistence-facing interfaces currently represented by mapper classes.
- `grpc`: gRPC endpoint adapters generated from the existing proto contract.
- `client`: outbound service/client abstractions.
- `config`: Spring configuration.
- `exception`: service exceptions, error codes, and REST exception handling.

The old top-level packages `manager`, `mapper`, and `entity` will be removed from the main source tree after their classes are migrated.

## Migration Rules

Move current `entity` classes to `model` and keep class names unchanged for now. This keeps the refactor mechanical and avoids introducing unnecessary domain terminology before database behavior exists.

Move current `mapper` interfaces to `repository` and rename each `*Mapper` interface to `*Repository`. For example, `UserMapper` becomes `UserRepository`. These interfaces currently contain no database methods, and their new names match the target MVC structure.

Move current `manager` behavior into `service`. Placeholder managers become service-level collaborators or are removed if unused. The phone verification code behavior becomes a service-layer component so `AuthService` no longer depends on a separate `manager` package.

Keep REST DTO and VO records in `controller` because they are transport-facing HTTP models. Do not move them to `model`; `model` is reserved for internal data models.

Keep `grpc` separate from `controller` because it is a distinct transport adapter with generated base classes and protobuf-specific concerns.

## Dependency Direction

`controller` and `grpc` depend on `service`.

`service` may depend on `model`, `repository`, and `client`.

`repository` depends on `model`.

`model` does not depend on Spring web, gRPC, controller DTOs, or repository interfaces.

`config` and `exception` remain cross-cutting support packages.

## Testing

Update the structure test to assert the new top-level package set and to reject the old `manager`, `mapper`, and `entity` packages.

Update unit tests to import the migrated service/model/repository classes.

Run the Maven test suite and ensure existing behavior remains unchanged:

- phone login code creation still returns the same response envelope.
- phone login still consumes a generated code once.
- invalid or expired codes still produce the same service exception.
- REST exception handling still returns the same failure envelope.

## Non-Goals

This refactor will not introduce Spring Data repositories, database schema logic, real session token issuing, or real third-party login behavior.

This refactor will not change package root `com.dating.user`.

This refactor will not change generated protobuf code or the proto file.
