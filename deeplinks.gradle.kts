// Deeplinks DebloatPass intercepts: one template per link shape.
//
// A template is a deeplink with {url} where the address of the .pkpass sits:
//
//   scheme://host/path/{url}          the URL is the rest of the link (may be percent-encoded
//                                     as a whole: https%3A%2F%2F…)
//   scheme://host/path?name={url}     the URL is the value of query parameter "name"
//   …{url:base64}                     the URL is Base64 (standard or URL-safe, padding optional)
//
// To add a new adware viewer, add its link shape below and rebuild. Example: for
// "newwallet://add/https://host/card.pkpass" add "newwallet://add/{url}".
//
// From this list the build generates one manifest intent-filter per template (scheme + host +
// path, so only these exact shapes reach the app) and the parser in PassLink.kt. The build fails
// on a malformed template or a reserved scheme (http, https, content, file, …).

extra["deeplinks"] = listOf(
    "walletunion://import/{url}",
    "passeswallet://import/{url}",
    "walletpasses://import/{url}",
    "walletpasses://import?url={url}",
)
