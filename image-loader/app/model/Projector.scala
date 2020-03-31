package model

import java.io.{File, FileOutputStream}

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{ObjectMetadata, S3Object}
import com.gu.mediaservice.lib.ImageIngestOperations
import com.gu.mediaservice.lib.aws.S3Ops
import com.gu.mediaservice.lib.imaging.ImageOperations
import com.gu.mediaservice.lib.logging.RequestLoggingContext
import com.gu.mediaservice.lib.net.URI
import com.gu.mediaservice.model.{Image, UploadInfo}
import lib.imaging.{MimeTypeDetection, NoSuchImageExistsInS3}
import lib.{DigestedFile, ImageLoaderConfig}
import org.apache.tika.io.IOUtils
import org.joda.time.{DateTime, DateTimeZone}
import play.api.Logger

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

object Projector {

  import Uploader.toImageUploadOpsCfg

  def apply(config: ImageLoaderConfig, imageOps: ImageOperations)(implicit ec: ExecutionContext): Projector
  = new Projector(toImageUploadOpsCfg(config), S3Ops.buildS3Client(config), imageOps)
}

case class S3FileExtractedMetadata(
  uploadedBy: String,
  uploadTime: DateTime,
  uploadFileName: Option[String],
  picdarUrn: Option[String]
)

object S3FileExtractedMetadata {
  def apply(s3ObjectMetadata: ObjectMetadata): S3FileExtractedMetadata = {
    val lastModified = s3ObjectMetadata.getLastModified.toInstant.toString
    val fileUserMetadata = s3ObjectMetadata.getUserMetadata.asScala.toMap

    val uploadedBy = fileUserMetadata.getOrElse("uploaded_by", "re-ingester")
    val uploadedTimeRaw = fileUserMetadata.getOrElse("upload_time", lastModified)
    val uploadTime = new DateTime(uploadedTimeRaw).withZone(DateTimeZone.UTC)
    val picdarUrn = fileUserMetadata.get("identifier!picdarurn")

    val uploadFileNameRaw = fileUserMetadata.get("file_name")
    // The file name is URL encoded in  S3 metadata
    val uploadFileName = uploadFileNameRaw.map(URI.decode)

    S3FileExtractedMetadata(
      uploadedBy = uploadedBy,
      uploadTime = uploadTime,
      uploadFileName = uploadFileName,
      picdarUrn = picdarUrn,
    )
  }
}

class Projector(config: ImageUploadOpsCfg,
                s3: AmazonS3,
                imageOps: ImageOperations) {

  private val imageUploadProjectionOps = new ImageUploadProjectionOps(config, imageOps)

  def projectS3ImageById(imageUploadProjector: Projector, imageId: String, requestLoggingContext: RequestLoggingContext, tempFile: File)(implicit ec: ExecutionContext): Future[Option[Image]] = {
    Logger.info(s"Projecting image: $imageId")(requestLoggingContext.toMarker())
    Future {
      import ImageIngestOperations.fileKeyFromId
      val s3Key = fileKeyFromId(imageId)

      if (!s3.doesObjectExist(config.originalFileBucket, s3Key))
        throw new NoSuchImageExistsInS3(config.originalFileBucket, s3Key)

      Logger.info(s"object exists, getting s3 object at s3://${config.originalFileBucket}/$s3Key to perform Image projection")(requestLoggingContext.toMarker())

      val s3Source = s3.getObject(config.originalFileBucket, s3Key)
      val digestedFile = getSrcFileDigestForProjection(s3Source, imageId, requestLoggingContext, tempFile)
      val extractedS3Meta = S3FileExtractedMetadata(s3Source.getObjectMetadata)

      val finalImageFuture = imageUploadProjector.projectImage(digestedFile, extractedS3Meta, requestLoggingContext)
      val finalImage = Await.result(finalImageFuture, Duration.Inf)
      Some(finalImage)
    }
  }

  private def getSrcFileDigestForProjection(s3Src: S3Object, imageId: String, requestLoggingContext: RequestLoggingContext, tempFile: File) = {
    IOUtils.copy(s3Src.getObjectContent, new FileOutputStream(tempFile))
    DigestedFile(tempFile, imageId)
  }

  def projectImage(srcFileDigest: DigestedFile, extractedS3Meta: S3FileExtractedMetadata, requestLoggingContext: RequestLoggingContext)(implicit ec: ExecutionContext): Future[Image] = {
    import extractedS3Meta._
    val DigestedFile(tempFile_, id_) = srcFileDigest
    // TODO more identifiers_ to rehydrate
    val identifiers_ = picdarUrn match {
      case Some(value) => Map[String, String]("picdarURN" -> value)
      case _ => Map[String, String]()
    }
    val uploadInfo_ = UploadInfo(filename = uploadFileName)
    //  Abort early if unsupported mime-type
    val mimeType_ = MimeTypeDetection.guessMimeType(tempFile_)

    val uploadRequest = UploadRequest(
      requestId = requestLoggingContext.requestId,
      imageId = id_,
      tempFile = tempFile_,
      mimeType = mimeType_,
      uploadTime = uploadTime,
      uploadedBy,
      identifiers = identifiers_,
      uploadInfo = uploadInfo_
    )

    imageUploadProjectionOps.projectImageFromUploadRequest(uploadRequest, requestLoggingContext)
  }

}

class ImageUploadProjectionOps(config: ImageUploadOpsCfg,
                               imageOps: ImageOperations) {

  import Uploader.{fromUploadRequestShared, toMetaMap}


  def projectImageFromUploadRequest(uploadRequest: UploadRequest, requestLoggingContext: RequestLoggingContext)
                                   (implicit ec: ExecutionContext): Future[Image] = {
    val dependenciesWithProjectionsOnly = ImageUploadOpsDependencies(config, imageOps,
    projectOriginalFileAsS3Model, projectThumbnailFileAsS3Model, projectOptimisedPNGFileAsS3Model)
    fromUploadRequestShared(uploadRequest, dependenciesWithProjectionsOnly, requestLoggingContext)
  }

  private def projectOriginalFileAsS3Model(uploadRequest: UploadRequest)
                                          (implicit ec: ExecutionContext)= Future {
    val meta: Map[String, String] = toMetaMap(uploadRequest)
    val key = ImageIngestOperations.fileKeyFromId(uploadRequest.imageId)
    S3Ops.projectFileAsS3Object(
      config.originalFileBucket,
      key,
      uploadRequest.tempFile,
      uploadRequest.mimeType,
      meta
    )
  }

  private def projectThumbnailFileAsS3Model(uploadRequest: UploadRequest, thumbFile: File)(implicit ec: ExecutionContext) = Future {
    val key = ImageIngestOperations.fileKeyFromId(uploadRequest.imageId)
    val thumbMimeType = Some("image/jpeg")
    S3Ops.projectFileAsS3Object(
      config.thumbBucket,
      key,
      thumbFile,
      thumbMimeType
    )
  }

  private def projectOptimisedPNGFileAsS3Model(uploadRequest: UploadRequest, optimisedPngFile: File)(implicit ec: ExecutionContext) = Future {
    val key = ImageIngestOperations.optimisedPngKeyFromId(uploadRequest.imageId)
    val optimisedPngMimeType = Some("image/png")
    S3Ops.projectFileAsS3Object(
      config.originalFileBucket,
      key,
      optimisedPngFile,
      optimisedPngMimeType
    )
  }

}