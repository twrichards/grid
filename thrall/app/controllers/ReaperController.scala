package controllers

import akka.actor.Scheduler
import com.gu.mediaservice.lib.ImageIngestOperations
import com.gu.mediaservice.lib.auth.Permissions.DeleteImage
import com.gu.mediaservice.lib.auth.{Authentication, Authorisation, BaseControllerWithLoginRedirects}
import com.gu.mediaservice.lib.config.Services
import com.gu.mediaservice.lib.elasticsearch.ReapableEligibility
import com.gu.mediaservice.lib.logging.{GridLogging, MarkerMap}
import com.gu.mediaservice.lib.metadata.SoftDeletedMetadataTable
import com.gu.mediaservice.model.{ImageStatusRecord, SoftDeletedMetadata}
import lib.{BatchDeletionIds, ThrallConfig, ThrallMetrics, ThrallStore}
import lib.elasticsearch.ElasticSearch
import org.joda.time.{DateTime, DateTimeZone}
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, AnyContent, ControllerComponents}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.collectionAsScalaIterableConverter
import scala.language.postfixOps

class ReaperController(
  es: ElasticSearch,
  store: ThrallStore,
  authorisation: Authorisation,
  config: ThrallConfig,
  scheduler: Scheduler,
  softDeletedMetadataTable: SoftDeletedMetadataTable,
  metrics: ThrallMetrics,
  override val auth: Authentication,
  override val services: Services,
  override val controllerComponents: ControllerComponents,
)(implicit val ec: ExecutionContext) extends BaseControllerWithLoginRedirects with GridLogging {

  private val CONTROL_FILE_NAME = "PAUSED"

  private val INTERVAL = 15 minutes // based on max of 1000 per reap, this interval will max out at 96,000 images per day

  implicit val logMarker: MarkerMap = MarkerMap()

  private val isReapable = new ReapableEligibility {
    override val persistedRootCollections: List[String] = config.persistedRootCollections
    override val persistenceIdentifier: String = config.persistenceIdentifier
  }

  (config.maybeReaperBucket, config.maybeReaperCountPerRun) match {
    case (Some(reaperBucket), Some(countOfImagesToReap)) =>
      scheduler.scheduleAtFixedRate(
        initialDelay = 0 seconds,
        interval = INTERVAL,
      ){ () =>
        if(store.client.doesObjectExist(reaperBucket, CONTROL_FILE_NAME)) {
          logger.info("Reaper is paused")
          es.countTotalSoftReapable(isReapable).map(metrics.softReapable.increment(Nil, _).run)
          es.countTotalHardReapable(isReapable).map(metrics.hardReapable.increment(Nil, _).run)
        } else {
          val deletedBy = "reaper"
          Future.sequence(Seq(
            doBatchSoftReap(countOfImagesToReap, deletedBy),
            doBatchHardReap(countOfImagesToReap, deletedBy)
          ))
        }
      }
    case _ => logger.info("scheduled reaper will not run since 's3.reaper.bucket' and 'reaper.countPerRun' need to be configured in thrall.conf")
  }

  private def batchDeleteWrapper(count: Int)(func: (Int, String) => Future[JsValue]) = auth.async { request =>
    if (!authorisation.hasPermissionTo(DeleteImage)(request.user)) {
      Future.successful(Forbidden)
    }
    else if (count > 1000) {
      Future.successful(BadRequest("Too many IDs. Maximum 1000."))
    }
    else {
      func(
        count,
        request.user.accessor.identity
      ).map(Ok(_))
    }
  }

  private def s3DirNameFromDate(date: DateTime) = date.toString("YYYY-MM-dd")

  private def persistedBatchDeleteOperation(deleteType: String)(doBatchDelete: => Future[JsValue]) = config.maybeReaperBucket match {
    case None => Future.failed(new Exception("Reaper bucket not configured"))
    case Some(reaperBucket) => doBatchDelete.map { json =>
      val now = DateTime.now(DateTimeZone.UTC)
      val key = s"$deleteType/${s3DirNameFromDate(now)}/$deleteType-${now.toString()}.json"
      store.client.putObject(reaperBucket, key, json.toString())
      json
    }
  }

  def doBatchSoftReap(count: Int): Action[AnyContent] = batchDeleteWrapper(count)(doBatchSoftReap)

  def doBatchSoftReap(count: Int, deletedBy: String): Future[JsValue] = persistedBatchDeleteOperation("soft"){

    es.countTotalSoftReapable(isReapable).map(metrics.softReapable.increment(Nil, _).run)

    logger.info(s"Soft deleting next $count images...")

    val deleteTime = DateTime.now(DateTimeZone.UTC)

    (for {
      BatchDeletionIds(esIds, esIdsActuallySoftDeleted) <- es.softDeleteNextBatchOfImages(isReapable, count, SoftDeletedMetadata(deleteTime, deletedBy))
      idsNotProcessedInDynamo <- softDeletedMetadataTable.setStatuses(esIdsActuallySoftDeleted.map(
        ImageStatusRecord(
          _,
          deletedBy,
          deleteTime = deleteTime.toString,
          isDeleted = true
        )
      ))
    } yield {
      metrics.softReaped.increment(n = esIdsActuallySoftDeleted.size).run
      esIds.map { id =>
        val wasSoftDeletedInES = esIdsActuallySoftDeleted.contains(id)
        val detail = Map(
          "ES" -> wasSoftDeletedInES,
          "dynamo.table.softDelete.metadata" -> (wasSoftDeletedInES && !idsNotProcessedInDynamo.contains(id))
        )
        logger.info(s"Soft deleted image $id : $detail")
        id -> detail
      }.toMap
    }).map(Json.toJson(_))
  }



  def doBatchHardReap(count: Int): Action[AnyContent] = batchDeleteWrapper(count)(doBatchHardReap)

  def doBatchHardReap(count: Int, deletedBy: String): Future[JsValue] = persistedBatchDeleteOperation("hard"){

    es.countTotalHardReapable(isReapable).map(metrics.hardReapable.increment(Nil, _).run)

    logger.info(s"Hard deleting next $count images...")

    (for {
      BatchDeletionIds(esIds, esIdsActuallyDeleted) <- es.hardDeleteNextBatchOfImages(isReapable, count)
      mainImagesS3Deletions <- store.deleteOriginals(esIdsActuallyDeleted)
      thumbsS3Deletions <- store.deleteThumbnails(esIdsActuallyDeleted)
      pngsS3Deletions <- store.deletePNGs(esIdsActuallyDeleted)
      idsNotProcessedInDynamo <- softDeletedMetadataTable.clearStatuses(esIdsActuallyDeleted)
    } yield {
      metrics.hardReaped.increment(n = esIdsActuallyDeleted.size).run
      esIds.map { id =>
        val wasHardDeletedFromES = esIdsActuallyDeleted.contains(id)
        val detail = Map(
          "ES" -> Some(wasHardDeletedFromES),
          "mainImage" -> mainImagesS3Deletions.get(ImageIngestOperations.fileKeyFromId(id)),
          "thumb" -> thumbsS3Deletions.get(ImageIngestOperations.fileKeyFromId(id)),
          "optimisedPng" -> pngsS3Deletions.get(ImageIngestOperations.optimisedPngKeyFromId(id)),
          "dynamo.table.softDelete.metadata" -> (if(wasHardDeletedFromES) Some(!idsNotProcessedInDynamo.contains(id)) else None)
        )
        logger.info(s"Hard deleted image $id : $detail")
        id -> detail
      }.toMap
    }).map(Json.toJson(_))
  }
  def index = withLoginRedirect {
    val now = DateTime.now(DateTimeZone.UTC)
    config.maybeReaperBucket match {
    case None => NotImplemented("Reaper bucket not configured")
    case Some(reaperBucket) =>
      val isPaused = store.client.doesObjectExist(reaperBucket, CONTROL_FILE_NAME)
      val recentRecords = List(now, now.minusDays(1), now.minusDays(2)).flatMap { day =>
        val s3DirName = s3DirNameFromDate(day)
        store.client.listObjects(reaperBucket, s"soft/$s3DirName/").getObjectSummaries.asScala.toList ++
          store.client.listObjects(reaperBucket, s"hard/$s3DirName/").getObjectSummaries.asScala.toList
      }

      val recentRecordKeys = recentRecords
        .filter(_.getLastModified after now.minusHours(48).toDate)
        .sortBy(_.getLastModified)
        .reverse
        .map(_.getKey)

      Ok(views.html.reaper(isPaused, INTERVAL.toString(), recentRecordKeys))
  }}

  def reaperRecord(key: String) = auth { config.maybeReaperBucket match {
    case None => NotImplemented("Reaper bucket not configured")
    case Some(reaperBucket) =>
      Ok(
        store.client.getObjectAsString(reaperBucket, key)
      ).as(JSON)
  }}

  def pauseReaper = auth { config.maybeReaperBucket match {
    case None => NotImplemented("Reaper bucket not configured")
    case Some(reaperBucket) =>
      store.client.putObject(reaperBucket, CONTROL_FILE_NAME, "")
      Redirect(routes.ReaperController.index)
  }}

  def resumeReaper = auth { config.maybeReaperBucket match {
    case None => NotImplemented("Reaper bucket not configured")
    case Some(reaperBucket) =>
      store.client.deleteObject(reaperBucket, CONTROL_FILE_NAME)
      Redirect(routes.ReaperController.index)
  }}

}
