package com.gu.mediaservice.lib.auth

import com.gu.mediaservice.lib.auth.Authentication.Principal

/** The name of the permission that we are checking, e.g. edit_collection */
sealed trait Permission[T] {
  def name: String = getClass.getSimpleName
}

sealed trait SimplePermission extends Permission[Unit]

object Permissions {
  /** A predicate that takes a principal and returns a boolean reflecting whether the principal has permission or not */
  type PrincipalFilter = Principal => Boolean

  case object EditMetadata extends SimplePermission
  case object DeleteImage extends SimplePermission
  case object DeleteCrops extends SimplePermission
  case object ShowPaid extends SimplePermission
}