package com.gu.mediaservice.lib

import java.io.File

import com.gu.mediaservice.lib.config.CommonConfig
import com.gu.mediaservice.lib.aws.S3Object
import com.gu.mediaservice.lib.logging.LogMarker
import com.gu.mediaservice.model.MimeType

import scala.concurrent.{ExecutionContext, Future}

object ImageIngestOperations {
  def fileKeyFromId(id: String): String = id.take(6).mkString("/") + "/" + id

  def optimisedPngKeyFromId(id: String): String = "optimised/" + fileKeyFromId(id: String)
}

class ImageIngestOperations(imageBucket: String, thumbnailBucket: String, config: CommonConfig, isVersionedS3: Boolean = false)
  extends S3ImageStorage(config) {

  import ImageIngestOperations.{fileKeyFromId, optimisedPngKeyFromId}

  def store(storableImage: StorableImage)
           (implicit logMarker: LogMarker): Future[S3Object] = storableImage match {
    case s:StorableOriginalImage => storeOriginalImage(s)
    case s:StorableThumbImage => storeThumbnailImage(s)
    case s:StorableOptimisedImage => storeOptimisedImage(s)
  }

  def fetchImage(id: String, file: File)(implicit ex: ExecutionContext, logMarker: LogMarker): Future[Map[String, String]] =
    fetch(imageBucket, fileKeyFromId(id), file)(ex, logMarker)

  private def storeOriginalImage(storableImage: StorableOriginalImage)
                        (implicit logMarker: LogMarker): Future[S3Object] =
    storeImage(imageBucket, fileKeyFromId(storableImage.id), storableImage.file, Some(storableImage.mimeType), storableImage.meta)

  private def storeThumbnailImage(storableImage: StorableThumbImage)
                         (implicit logMarker: LogMarker): Future[S3Object] =
    storeImage(thumbnailBucket, fileKeyFromId(storableImage.id), storableImage.file, Some(storableImage.mimeType))

  private def storeOptimisedImage(storableImage: StorableOptimisedImage)
                       (implicit logMarker: LogMarker): Future[S3Object] =
    storeImage(imageBucket, optimisedPngKeyFromId(storableImage.id), storableImage.file, Some(storableImage.mimeType))

  def deleteOriginal(id: String): Future[Unit] = if(isVersionedS3) deleteVersionedImage(imageBucket, fileKeyFromId(id)) else deleteImage(imageBucket, fileKeyFromId(id))
  def deleteThumbnail(id: String): Future[Unit] = deleteImage(thumbnailBucket, fileKeyFromId(id))
  def deletePng(id: String): Future[Unit] = deleteImage(imageBucket, optimisedPngKeyFromId(id))
}

sealed trait ImageWrapper {
  val id: String
  val file: File
  val mimeType: MimeType
  val meta: Map[String, String]
}
sealed trait StorableImage extends ImageWrapper

case class StorableThumbImage(id: String, file: File, mimeType: MimeType, meta: Map[String, String] = Map.empty) extends StorableImage
case class StorableOriginalImage(id: String, file: File, mimeType: MimeType, meta: Map[String, String] = Map.empty) extends StorableImage
case class StorableOptimisedImage(id: String, file: File, mimeType: MimeType, meta: Map[String, String] = Map.empty) extends StorableImage
case class BrowserViewableImage(id: String, file: File, mimeType: MimeType, meta: Map[String, String] = Map.empty, mustUpload: Boolean = false) extends ImageWrapper {
  def asStorableOptimisedImage = StorableOptimisedImage(id, file, mimeType, meta)
  def asStorableThumbImage = StorableThumbImage(id, file, mimeType, meta)
}

