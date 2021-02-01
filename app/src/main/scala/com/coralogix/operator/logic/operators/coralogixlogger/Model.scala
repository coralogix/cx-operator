package com.coralogix.operator.logic.operators.coralogixlogger

import com.coralogix.zio.k8s.client.com.coralogix.loggers.definitions.coralogixlogger.v1.CoralogixLogger
import com.coralogix.zio.k8s.model.apps.v1.{ DaemonSet, DaemonSetSpec }
import com.coralogix.zio.k8s.model.core.v1._
import com.coralogix.zio.k8s.model.pkg.api.resource.Quantity
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ LabelSelector, ObjectMeta }
import com.coralogix.zio.k8s.model.rbac.v1._

object Model {

  private def labelsFor(name: String): Map[String, String] =
    Map(
      "k8s-app" -> s"fluentd-coralogix-$name"
    )

  def serviceAccount(name: String, resource: CoralogixLogger): ServiceAccount =
    ServiceAccount(
      metadata = ObjectMeta(
        name = "fluentd-coralogix-service-account",
        namespace = resource.metadata.flatMap(_.namespace),
        labels = labelsFor(name)
      )
    )

  def clusterRole(name: String, resource: CoralogixLogger): ClusterRole =
    ClusterRole(
      metadata = ObjectMeta(
        name = "fluentd-coralogix-role",
        namespace = resource.metadata.flatMap(_.namespace),
        labels = labelsFor(name)
      ),
      rules = Vector(
        PolicyRule(
          apiGroups = Vector(""),
          resources = Vector(
            "namespaces",
            "pods"
          ),
          verbs = Vector(
            "get",
            "list",
            "watch"
          )
        )
      )
    )

  def clusterRoleBinding(name: String, resource: CoralogixLogger): ClusterRoleBinding =
    ClusterRoleBinding(
      metadata = ObjectMeta(
        name = "fluentd-coralogix-role-binding",
        namespace = resource.metadata.flatMap(_.namespace),
        labels = labelsFor(name)
      ),
      roleRef = RoleRef(
        apiGroup = "rbac.authorization.k8s.io",
        kind = "ClusterRole",
        name = "fluentd-coralogix-role"
      ),
      subjects = Vector(
        Subject(
          kind = "ServiceAccount",
          name = "fluentd-coralogix-service-account",
          namespace = resource.metadata.flatMap(_.namespace)
        )
      )
    )

  def daemonSet(name: String, resource: CoralogixLogger): DaemonSet =
    DaemonSet(
      metadata = ObjectMeta(
        name = s"fluentd-coralogix-$name",
        namespace = resource.metadata.flatMap(_.namespace),
        labels = labelsFor(name) + ("kubernetes.io/cluster-service" -> "true")
      ),
      spec = DaemonSetSpec(
        selector = LabelSelector(
          matchLabels = Map(
            "k8s-app" -> s"fluentd-coralogix-$name"
          )
        ),
        template = PodTemplateSpec(
          metadata = ObjectMeta(
            labels = labelsFor(name) + ("kubernetes.io/cluster-service" -> "true")
          ),
          spec = PodSpec(
            containers = Vector(
              Container(
                name = "fluentd",
                image = "registry.connect.redhat.com/coralogix/coralogix-fluentd:1.0.0",
                imagePullPolicy = "Always",
                securityContext = SecurityContext(
                  runAsUser = 0L,
                  privileged = true
                ),
                env = Vector(
                  EnvVar(
                    name = "CORALOGIX_PRIVATE_KEY",
                    value = resource.spec.map(_.privateKey)
                  ),
                  EnvVar(
                    name = "CLUSTER_NAME",
                    value = resource.spec.map(_.clusterName)
                  )
                ),
                resources = ResourceRequirements(
                  requests = Map(
                    "cpu"    -> Quantity("100m"),
                    "memory" -> Quantity("400Mi")
                  )
                ),
                volumeMounts = Vector(
                  VolumeMount(
                    name = "varlog",
                    mountPath = "/var/log"
                  ),
                  VolumeMount(
                    name = "varlibdockercontainers",
                    mountPath = "/var/lib/docker/containers",
                    readOnly = true
                  )
                )
              )
            ),
            volumes = Vector(
              Volume(
                name = "varlog",
                hostPath = HostPathVolumeSource(
                  path = "/var/log"
                )
              ),
              Volume(
                name = "varlibdockercontainers",
                hostPath = HostPathVolumeSource(
                  path = "/var/lib/docker/containers"
                )
              )
            ),
            serviceAccountName = "fluentd-coralogix-service-account"
          )
        )
      )
    )
}
