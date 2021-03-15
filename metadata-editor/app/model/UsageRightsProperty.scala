package model

import com.gu.mediaservice.lib.config.{Publication, PhotographersConfig}
import com.gu.mediaservice.model._
import play.api.libs.json._


// TODO: We'll be able to deprecate this and build it up directly from case
// classes.
// TODO: turn this into a case class?
case class UsageRightsProperty(
  name: String,
  label: String,
  `type`: String,
  required: Boolean,
  options: Option[List[String]] = None,
  optionsMap: Option[Map[String, List[String]]] = None,
  optionsMapKey: Option[String] = None,
  examples: Option[String] = None
)


object UsageRightsProperty {
  type OptionsMap = Map[String, List[String]]
  type Options = List[String]

  implicit val jsonWrites: Writes[UsageRightsProperty] = Json.writes[UsageRightsProperty]

  def sortList(l: List[String]) = l.sortWith(_.toLowerCase < _.toLowerCase)

  val props: List[(UsageRightsSpec, PhotographersConfig) => List[UsageRightsProperty]] =
    List(categoryUsageRightsProperties, restrictionProperties)

  def publicationListToMap(publications: List[Publication]): OptionsMap = Map(publications
    .map(p => p.name -> p.photographers): _*)

  def optionsFromPublicationList(publications: List[Publication]): Options = sortList(publicationListToMap(publications).keys.toList)

  def getPropertiesForSpec(u: UsageRightsSpec, p: PhotographersConfig): List[UsageRightsProperty] = props.flatMap(f => f(u, p))

  private def requiredStringField(
    name: String,
    label: String,
    options: Option[List[String]] = None,
    examples: Option[String] = None,
    optionsMap: Option[Map[String, List[String]]] = None,
    optionsMapKey: Option[String] = None
  ) = UsageRightsProperty(name, label, "string", required = true, options,
                          optionsMap, optionsMapKey, examples)

  private def publicationField(required: Boolean): UsageRightsProperty = publicationField(required, List.empty)

  private def publicationField(required: Boolean, options: Options)  =
    UsageRightsProperty("publication", "Publication", "string", required,
      Some(sortList(options)))

  private def photographerField(examples: String) =
    requiredStringField("photographer", "Photographer", examples = Some(examples))

  private def photographerField(photographers: List[Publication], key: String) =
    requiredStringField("photographer", "Photographer",
      optionsMap = Some(publicationListToMap(photographers)), optionsMapKey = Some(key))

  private def illustratorField(illustrators: List[Publication], key: String) =
    requiredStringField("creator", "Illustrator",
      optionsMap = Some(publicationListToMap(illustrators)), optionsMapKey = Some(key))

  private def restrictionProperties(u: UsageRightsSpec, p: PhotographersConfig): List[UsageRightsProperty] = u match {
    case NoRights => List()
    case _ => List(UsageRightsProperty("restrictions", "Restrictions", "text", u.defaultCost.contains(Conditional)))
  }

  def categoryUsageRightsProperties(u: UsageRightsSpec, p: PhotographersConfig) = u match {
    case Agency => List(
      requiredStringField("supplier", "Supplier", Some(sortList(PhotographersConfig.freeSuppliers))),
      UsageRightsProperty(
        "suppliersCollection", "Collection", "string", required = false,
        examples = Some("AFP, FilmMagic, WireImage"))
    )

    case CommissionedAgency => List(requiredStringField("supplier", "Supplier", examples = Some("Demotix")))

    case StaffPhotographer => List(
      publicationField(required = true, optionsFromPublicationList(p.staffPhotographers)),
      photographerField(p.staffPhotographers, "publication")
    )

    case ContractPhotographer => List(
      publicationField(required = true, optionsFromPublicationList(p.contractedPhotographers)),
      photographerField(p.contractedPhotographers, "publication")
    )

    case CommissionedPhotographer => List(
      publicationField(required = false),
      photographerField("Sophia Evans, Murdo MacLeod")
    )

    case ContractIllustrator => List(
      publicationField(required = true, optionsFromPublicationList(p.contractIllustrators)),
      illustratorField(p.contractIllustrators, "publication")
    )

    case StaffIllustrator => List(
      requiredStringField("creator", "Illustrator", Some(sortList(p.staffIllustrators))))

    case CommissionedIllustrator => List(
      publicationField(required = false),
      requiredStringField("creator", "Illustrator", examples = Some("Ellie Foreman Peck, Matt Bors")))

    case CreativeCommons => List(
      requiredStringField("licence", "Licence", Some(p.creativeCommonsLicense)),
      requiredStringField("source", "Source", examples = Some("Wikimedia Commons")),
      requiredStringField("creator", "Owner", examples = Some("User:Colin")),
      requiredStringField("contentLink", "Link to content", examples = Some("https://commons.wikimedia.org/wiki/File:Foreign_and_Commonwealth_Office_-_Durbar_Court.jpg"))
    )

    case Composite => List(
      requiredStringField("suppliers", "Suppliers", examples = Some("REX/Getty Images/Corbis, Corbis/Reuters"))
    )

    case Screengrab => List(
      requiredStringField("source", "Source", examples = Some("BBC News, HBO, ITV"))
    )

    case _ => List()
  }
}
