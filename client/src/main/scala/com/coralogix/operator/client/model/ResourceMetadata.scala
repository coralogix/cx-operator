package com.coralogix.operator.client.model

trait ResourceMetadata[T] {
  def kind: String
  def apiVersion: String
  def resourceType: K8sResourceType
}
