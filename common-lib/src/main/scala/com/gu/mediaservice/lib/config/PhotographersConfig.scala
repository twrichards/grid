package com.gu.mediaservice.lib.config

import com.gu.mediaservice.model.{ContractPhotographer, Photographer, StaffPhotographer}
import play.api.{ConfigLoader, Configuration}

import scala.collection.JavaConverters._

case class Publication(name: String, photographers: List[String])

object Publication {
  implicit val configLoader: ConfigLoader[List[Publication]] = ConfigLoader(_.getConfigList).map(
    _.asScala.map(
      config =>
        Publication(
          config.getString("name"),
          config.getStringList("photographers").asScala.toList)).toList
  )
}

case class PhotographersConfig(
  externalStaffPhotographers: List[Publication] = List(),
  internalStaffPhotographers: List[Publication] = List(),
  contractedPhotographers: List[Publication] = List(),
  contractIllustrators: List[Publication] = List(),
  staffIllustrators: List[String] = List(),
  creativeCommonsLicense: List[String] = List()
) {
  val staffPhotographers: List[Publication] = PhotographersConfig.flattenPublicationList(
    internalStaffPhotographers ++ externalStaffPhotographers)

  val allPhotographers: List[Publication] = PhotographersConfig.flattenPublicationList(
    internalStaffPhotographers ++ externalStaffPhotographers ++ contractedPhotographers)

  def getPhotographer(photographer: String): Option[Photographer] = {
    caseInsensitiveLookup(staffPhotographers, photographer).map {
      case (name, publication) => StaffPhotographer(name, publication)
    }.orElse(caseInsensitiveLookup(contractedPhotographers, photographer).map {
      case (name, publication) => ContractPhotographer(name, Some(publication))
    })
  }

  def caseInsensitiveLookup(store: List[Publication], lookup: String): Option[(String, String)] =
    store.map {
      case Publication(name, photographers) if photographers.map(_.toLowerCase) contains lookup.toLowerCase() => Some(lookup, name)
      case _ => None
    }.find(_.isDefined).flatten
}

object PhotographersConfig {

  implicit val configLoader: ConfigLoader[PhotographersConfig] = ConfigLoader(_.getConfig).map(config => {
    val configuration = Configuration(config)
    PhotographersConfig(
      configuration.get[List[Publication]]("externalStaffPhotographers"),
      configuration.get[List[Publication]]("internalStaffPhotographers"),
      configuration.get[List[Publication]]("contractedPhotographers"),
      configuration.get[List[Publication]]("contractIllustrators"),
      configuration.get[Seq[String]]("staffIllustrators").toList,
      configuration.get[Seq[String]]("creativeCommonsLicense").toList
    )
  })

  def flattenPublicationList(companies: List[Publication]): List[Publication] = companies
    .groupBy(_.name)
    .map { case (group, companies) => Publication(group, companies.flatMap(company => company.photographers)) }
    .toList

  val payGettySourceList = List(
    "Arnold Newman Collection",
    "360cities.net Editorial",
    "360cities.net RM",
    "age fotostock RM",
    "Alinari",
    "Arnold Newman Collection",
    "ASAblanca",
    "Bob Thomas Sports Photography",
    "Carnegie Museum of Art",
    "Catwalking",
    "Contour",
    "Contour RA",
    "Corbis Premium Historical",
    "Editorial Specials",
    "Reportage Archive",
    "Gamma-Legends",
    "Genuine Japan Editorial Stills",
    "Genuine Japan Creative Stills",
    "George Steinmetz",
    "Getty Images Sport Classic",
    "Iconic Images",
    "Iconica",
    "Icon Sport",
    "Kyodo News Stills",
    "Lichfield Studios Limited",
    "Lonely Planet Images",
    "Lonely Planet RF",
    "Masters",
    "Major League Baseball Platinum",
    "Moment Select",
    "Mondadori Portfolio Premium",
    "National Geographic",
    "National Geographic RF",
    "National Geographic Creative",
    "National Geographic Magazines",
    "NBA Classic",
    "Neil Leifer Collection",
    "Newspix",
    "PA Images",
    "Papixs",
    "Paris Match Archive",
    "Paris Match Collection",
    "Pele 10",
    "Photonica",
    "Photonica World",
    "Popperfoto",
    "Popperfoto Creative",
    "Premium Archive",
    "Reportage Archive",
    "SAMURAI JAPAN",
    "Sports Illustrated",
    "Sports Illustrated Classic",
    "Sygma Premium",
    "Terry O'Neill",
    "The Asahi Shimbun Premium",
    "The LIFE Premium Collection",
    "ullstein bild Premium",
    "Ulrich Baumgarten",
    "VII Premium",
    "Vision Media",
    "Xinhua News Agency"
  )

  val freeSuppliers = List(
    "AAP",
    "Alamy",
    "Allstar Picture Library",
    "AP",
    "EPA",
    "Getty Images",
    "PA",
    "Reuters",
    "Rex Features",
    "Ronald Grant Archive",
    "Action Images",
    "Action Images/Reuters"
  )

  val suppliersCollectionExcl = Map(
    "Getty Images" -> payGettySourceList
  )
}
