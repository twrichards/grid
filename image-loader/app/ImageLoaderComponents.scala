import com.gu.mediaservice.lib.cleanup.{MetadataCleaners, SupplierProcessors}
import com.gu.mediaservice.lib.config.MetadataStore
import com.gu.mediaservice.lib.imaging.ImageOperations
import com.gu.mediaservice.lib.play.GridComponents
import controllers.ImageLoaderController
import lib._
import lib.storage.ImageLoaderStore
import model.{ImageUploadOps, OptimisedPngOps}
import play.api.ApplicationLoader.Context
import router.Routes

class ImageLoaderComponents(context: Context) extends GridComponents(context) {
  final override lazy val config = new ImageLoaderConfig(configuration)

  final override val buildInfo = utils.buildinfo.BuildInfo

  val loaderStore = new ImageLoaderStore(config)
  val imageOperations = new ImageOperations(context.environment.rootPath.getAbsolutePath)

  val notifications = new Notifications(config)
  val downloader = new Downloader()
  val optimisedPngOps = new OptimisedPngOps(loaderStore, config)

  val metaDataConfigStore = MetadataStore(config.configBucket, config)
  metaDataConfigStore.scheduleUpdates(actorSystem.scheduler)

  val metadataCleaners = new MetadataCleaners(metaDataConfigStore)
  val supplierProcessors = new SupplierProcessors(metaDataConfigStore)

  val imageUploadOps = new ImageUploadOps(loaderStore, config, imageOperations, optimisedPngOps, metadataCleaners, supplierProcessors)

  val controller = new ImageLoaderController(auth, downloader, loaderStore, notifications, config, imageUploadOps, controllerComponents, wsClient)

  override lazy val router = new Routes(httpErrorHandler, controller, management)
}
