package com.autopilot.core.common

import kotlinx.serialization.Serializable

/**
 * Hardware-truth provenance levels for protocol implementations and device capabilities.
 */
@Serializable
enum class VerificationStatus {
  IMPLEMENTED,
  TESTED,
  PHYSICALLY_VERIFIED,
  EXPERIMENTAL,
  SUSPECTED,
  UNSUPPORTED,
  BLOCKED,
}

/**
 * Engineering implementation status per governance specification.
 */
@Serializable
enum class ImplementationStatus {
  COMPLETE_AND_VERIFIED,
  IMPLEMENTED_UNVERIFIED,
  PARTIALLY_IMPLEMENTED,
  EXPERIMENTAL,
  SIMULATED,
  BLOCKED,
  NOT_IMPLEMENTED,
}
