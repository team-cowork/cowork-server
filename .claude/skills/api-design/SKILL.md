---
name: api-design
description: REST API design guide for new endpoints — RESTful URL structure, query parameter binding rules (@RequestParam vs @ModelAttribute), OpenAPI annotations, and CommonApiResponse usage.
---

# REST API Design Guide

Use the target module's controller, Gateway route configuration, and `CONTRIBUTING.md` together. Public routes are Gateway contracts; do not infer a service-local path by adding `/v1` to every controller.

## URL Design

- Use domain resources such as `/teams`, `/projects`, and `/channels`.
- Preserve existing resource hierarchy and HTTP method semantics.
- Check `cowork-config/src/main/resources/configs/cowork-gateway-{local,prod}.yml` for public prefixes and rewrites.

## Query Parameters

For Spring controllers, use `@RequestParam` for one or two simple query values. Use `@Valid @ModelAttribute` with a request DTO when there are three or more values or grouped validation is needed. Path values use `@PathVariable`.

Use `queryReq` for a general query and `searchReq` for a search. Preserve the endpoint's pagination and sorting contract; `page`/`size` are not universal across services.

## OpenAPI Documentation

Document the actual HTTP status, request validation, and response type. Kotlin request DTOs use `@param:Schema`, response DTOs use `@field:Schema`, and Bean Validation/Jackson annotations use `@field:` under the project convention.

Use the module's existing DTO names: team/channel commonly use `Request`/`Response`; project uses `ReqDto`/`ResDto`. Non-Spring services use their runtime's validation and OpenAPI facilities.

## Response Format

- Return data DTOs directly in Spring MVC controllers. Check the module's `sdk.response` settings and the Gateway wrapper before describing the externally visible envelope.
- Team enables SDK wrapping; channel/project disable it and use the Gateway response contract. A dependency on the SDK alone does not enable every feature.
- Use an explicit no-data response or status only when required by that endpoint. Preserve `204` and streaming semantics.
- Throw `ExpectedException` for Spring business errors and use the module's configured handler. Downstream HTTP services authorize the Gateway-forwarded user headers; they do not verify JWT themselves.
