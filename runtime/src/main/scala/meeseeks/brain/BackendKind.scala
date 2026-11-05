package meeseeks.brain

/** The three pluggable backend kinds for brain and oracle inference. */
enum BackendKind:
  case Subscription
  case ApiKey
  case LocalModel
