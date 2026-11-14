// Deeplink schemes DebloatPass intercepts.
//
// To add a new adware viewer: add its scheme (the part before "://") below and rebuild.
// Example: for "newwallet://import/https://host/card.pkpass", add "newwallet".
//
// These link shapes are understood without any code changes:
//   scheme://import/https://host/x.pkpass
//   scheme://anything/https://host/x.pkpass
//   scheme://import?url=https%3A%2F%2Fhost%2Fx.pkpass
//
// The intent-filter in the manifest and the parser in PassLink.kt are both generated from this list.

extra["deeplinkSchemes"] = listOf(
    "walletunion",
    "passeswallet",
    "walletpasses",
)
