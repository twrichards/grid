package com.gu.mediaservice.lib.feature

import play.api.mvc.{Cookie, Request}

import scala.util.Try

case class FeatureSwitch(key: String, title: String, default: Boolean) {
  val name = s"feature-switch-$key"

  def getValue[A](request: Request[A]): Boolean =
    request.cookies.get(name).flatMap(toBooleanOption).getOrElse(default)

  private def toBooleanOption(c: Cookie): Option[Boolean] =
    Try(c.value.toBoolean).toOption
}

object ExampleSwitch extends FeatureSwitch(
  key = "example-switch",
  title = "An example switch. Use rounded corners for the feature switch toggle",
  default = false
)
object VipsImagingSwitch extends FeatureSwitch(
  key = "vips-beta",
  title = "Enable the VIPS upload pipeline, where implemented",
  default = false
)

object FeatureSwitches {
  val all: List[FeatureSwitch] = List(ExampleSwitch, VipsImagingSwitch)
}

class FeatureSwitches(featureSwitches: List[FeatureSwitch]) {
  // Feature switches are defined here, but updated by setting a cookie following the pattern e.g. "feature-switch-my-key"
  // for a switch called "my-key".

  def getFeatureSwitchCookies(cookieGetter: String => Option[Cookie]): List[(FeatureSwitch, Option[Cookie])] =
    featureSwitches.map(featureSwitch => (featureSwitch, cookieGetter(featureSwitch.name)))

  def getClientSwitchValues(featureSwitchesWithCookies: List[(FeatureSwitch, Option[Cookie])]): Map[FeatureSwitch, Boolean] = {
    featureSwitchesWithCookies
      .map {
        case (featureSwitch, Some(cookie)) => (featureSwitch, getBoolean(cookie.value))
        case (featureSwitch, None) => (featureSwitch, featureSwitch.default)
      }
      .toMap
  }

  def getFeatureSwitchesToStringify(clientSwitchValues: Map[FeatureSwitch, Boolean]): List[Map[String, String]] = {
    clientSwitchValues.map {
      case (featureSwitch, value) => Map(
        "key" -> featureSwitch.key,
        "title" -> featureSwitch.title,
        "value" -> value.toString
      )
    }.toList
  }

  def getFeatureSwitchValue(clientSwitchValues: Map[FeatureSwitch, Boolean], key: String): Boolean = {
    // A getter to use the client-controlled feature switches within this Scala backend
    val maybeSwitch = featureSwitches.find(switch => switch.key == key)
    maybeSwitch.flatMap(switch => clientSwitchValues.get(switch)).getOrElse(false)
  }

  private def getBoolean(cookieValue: String): Boolean = cookieValue == "true"
}
