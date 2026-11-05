package meeseeks.research

/** The result of fetching a source URL for a claim. */
enum UrlStatus:
  case Supported
  case NotSupported
  case Unreachable
