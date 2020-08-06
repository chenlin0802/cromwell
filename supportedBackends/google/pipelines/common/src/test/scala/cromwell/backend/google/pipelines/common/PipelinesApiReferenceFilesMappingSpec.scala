package cromwell.backend.google.pipelines.common

import cats.effect.IO
import cromwell.backend.google.pipelines.common.PipelinesApiReferenceFilesMapping.{ManifestFile, ReferenceFile}
import cromwell.backend.google.pipelines.common.io.PipelinesApiReferenceFilesDisk
import cromwell.cloudsupport.gcp.auth.ApplicationDefaultMode
import cromwell.filesystems.gcs.GcsPathBuilder.ValidFullGcsPath
import org.scalatest.{FlatSpecLike, Matchers}

class PipelinesApiReferenceFilesMappingSpec extends FlatSpecLike with Matchers {

  private val refFile1Disk1 = "bucketname/dir1/dir2/filename1"
  private val refFile2Disk1 = "bucketname/dir1/dir2/dir3/filename2"
  private val refFile3Disk2 = "bucketname2/dir1/filename"
  private val refFile4Disk2MismatchingChecksum = "bucketname2/dir1/dir2/filename"

  private val disk1 = PipelinesApiReferenceFilesDisk("image_identifier_1", 500)
  private val disk2 = PipelinesApiReferenceFilesDisk("image_identifier_2", 100)

  it should "correctly figure out which disks have to be mounted based on provided input file paths" in {
    val nonReferenceInputFilePaths = Set("gs://not/a/reference/file")
    val forNonReferenceFile = refFileMappingsMock.getReferenceDisksToMount(nonReferenceInputFilePaths)
    forNonReferenceFile.isEmpty shouldBe true

    val referenceInputFilePathsFrom2Disks = Set(s"gs://$refFile1Disk1", s"gs://$refFile3Disk2")
    val forReferencesFrom2Disks = refFileMappingsMock.getReferenceDisksToMount(referenceInputFilePathsFrom2Disks)
    forReferencesFrom2Disks should contain theSameElementsAs List(disk1, disk2)

    val referenceInputFilePathsFromSingleDisk = Set(s"gs://$refFile1Disk1", s"gs://$refFile2Disk1")
    val forReferencesFromSingleDisk = refFileMappingsMock.getReferenceDisksToMount(referenceInputFilePathsFromSingleDisk)
    forReferencesFromSingleDisk.size shouldBe 1
    forReferencesFromSingleDisk.head shouldBe disk1
  }

  it should "not consider valid a reference file with mismatching checksum" in {
    val mismatchingChecksumReferenceFile = Set(refFile4Disk2MismatchingChecksum)
    val forMismatchingChecksumReferenceFile = refFileMappingsMock.getReferenceDisksToMount(mismatchingChecksumReferenceFile)
    forMismatchingChecksumReferenceFile.isEmpty shouldBe true
  }

  private val refFileMappingsMock =
    new PipelinesApiReferenceFilesMapping (
      ApplicationDefaultMode("default"),
      Some (
        List (
          ValidFullGcsPath("bucketname", "manifest1"),
          ValidFullGcsPath("bucketname2", "manifest2"),
        )
      )
    ) {
      override def readReferenceDiskManifestFileFromGCS(gcsPath: ValidFullGcsPath): IO[ManifestFile] = {
        IO.pure {
          gcsPath match {
            case ValidFullGcsPath("bucketname", "manifest1") =>
              ManifestFile(
                imageIdentifier = disk1.image,
                diskSizeGb = 500,
                files = List(
                  ReferenceFile(path = refFile1Disk1, crc32c = 12345L),
                  ReferenceFile(path = refFile2Disk1, crc32c = 12345678L)
                )
              )
            case ValidFullGcsPath("bucketname2", "manifest2") =>
              ManifestFile(
                imageIdentifier = disk2.image,
                diskSizeGb = 100,
                files = List(
                  ReferenceFile(path = refFile3Disk2, crc32c = 12L),
                  ReferenceFile(path = refFile4Disk2MismatchingChecksum, crc32c = 12345L),
                )
              )
            case _ => throw new RuntimeException("Exception in mock object. This should not happen")
          }
        }
      }

      override protected def bulkValidateCrc32cs(referenceFiles: Set[ReferenceFile]): IO[Map[ReferenceFile, Boolean]] = {
        IO.pure(referenceFiles.map(file => (file, file.path != refFile4Disk2MismatchingChecksum)).toMap)
      }
    }
}
