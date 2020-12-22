package com.coralogix.operator.client

import com.coralogix.operator.client.model.{ K8sCluster, K8sResourceType, TypedWatchEvent }
import com.coralogix.operator.client.model.generated.apiextensions.v1.{
  CustomResourceDefinition,
  CustomResourceDefinitionStatus
}
import com.coralogix.operator.client.model.generated.apimachinery.v1.{ DeleteOptions, Status }
import sttp.client3.httpclient.zio.SttpClient
import zio.clock.Clock
import zio.{ Has, ZIO, ZLayer }
import zio.config.ZConfig
import zio.stream.ZStream

package object crd {
  type CustomResourceDefinitions =
    Has[ClusterResource[CustomResourceDefinitionStatus, CustomResourceDefinition]]

  def live: ZLayer[SttpClient with ZConfig[K8sCluster], Nothing, CustomResourceDefinitions] =
    ResourceClient.cluster.live[CustomResourceDefinitionStatus, CustomResourceDefinition](
      K8sResourceType(
        "customresourcedefinitions",
        "apiextensions.k8s.io",
        "v1"
      )
    )

  def getAll(
    chunkSize: Int = 10
  ): ZStream[CustomResourceDefinitions, K8sFailure, CustomResourceDefinition] =
    ResourceClient.cluster.getAll(chunkSize)

  def watch(
    resourceVersion: Option[String]
  ): ZStream[CustomResourceDefinitions, K8sFailure, TypedWatchEvent[CustomResourceDefinition]] =
    ResourceClient.cluster.watch(resourceVersion)

  def watchForever[T, R, E](
  ): ZStream[CustomResourceDefinitions with Clock, K8sFailure, TypedWatchEvent[
    CustomResourceDefinition
  ]] =
    ResourceClient.cluster.watchForever()

  def get(
    name: String
  ): ZIO[CustomResourceDefinitions, K8sFailure, CustomResourceDefinition] =
    ResourceClient.cluster.get(name)

  def create(
    newResource: CustomResourceDefinition,
    dryRun: Boolean = false
  ): ZIO[CustomResourceDefinitions, K8sFailure, CustomResourceDefinition] =
    ResourceClient.cluster.create(newResource, dryRun)

  def replace(
    name: String,
    updatedResource: CustomResourceDefinition,
    dryRun: Boolean = false
  ): ZIO[CustomResourceDefinitions, K8sFailure, CustomResourceDefinition] =
    ResourceClient.cluster.replace(name, updatedResource, dryRun)

  def replaceStatus(
    of: CustomResourceDefinition,
    updatedStatus: CustomResourceDefinitionStatus,
    dryRun: Boolean = false
  ): ZIO[CustomResourceDefinitions, K8sFailure, CustomResourceDefinition] =
    ResourceClient.cluster.replaceStatus(of, updatedStatus, dryRun)

  def delete(
    name: String,
    deleteOptions: DeleteOptions,
    dryRun: Boolean = false
  ): ZIO[CustomResourceDefinitions, K8sFailure, Status] =
    ResourceClient.cluster.delete(name, deleteOptions, dryRun)
}
