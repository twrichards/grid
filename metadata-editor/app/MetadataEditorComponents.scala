import com.gu.mediaservice.lib.imaging.ImageOperations
import com.gu.mediaservice.lib.play.GridComponents
import controllers.{EditsApi, EditsController}
import lib._
import play.api.ApplicationLoader.Context
import router.Routes

class MetadataEditorComponents(context: Context) extends GridComponents(context, new EditsConfig(_)) {
  final override val buildInfo = utils.buildinfo.BuildInfo

  val editsStore = new EditsStore(config)
  val syndicationStore = new SyndicationStore(config)
  val notifications = new Notifications(config)
  val imageOperations = new ImageOperations(context.environment.rootPath.getAbsolutePath)

  val metrics = new MetadataEditorMetrics(config)
  val messageConsumer = new MetadataSqsMessageConsumer(config, metrics, editsStore)

  messageConsumer.startSchedule()
  context.lifecycle.addStopHook {
    () => messageConsumer.actorSystem.terminate()
  }

  val editsController = new EditsController(auth, editsStore, notifications, config, controllerComponents)
  val syndicationController = new SyndicationController(auth, editsStore, syndicationStore, notifications, config, controllerComponents)
  val controller = new EditsApi(auth, config, controllerComponents)

  override val router = new Routes(httpErrorHandler, controller, editsController, management)
}
