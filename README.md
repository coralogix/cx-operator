# cx-operator

### Functionality
A Kubernetes _operator_ managing the following custom resource types:

- `rulegroupsets.coralogix.com`

#### Rule group sets
The `RuleGroupSet` custom resource describes one or more Coralogix [_rule groups_](https://coralogix.com/tutorials/log-parsing-rules/).
The operator adds/updates/removes Coralogix rule groups by reacting to
CRD events using the Coralogix gRPC API.

Example rule group set:

```yaml
apiVersion: "coralogix.com/v1"
kind: RuleGroupSet
metadata:
  name: test-rulegroupset-1
spec:
  rule-groups-sequence:
    - name: 'Operator Test Rules 1'
      matcher:
        applications:
          - app1
          - app2
      and-sequence:
        - or-group:
            - name: 'Delete prefix'
              enabled: true
              source-field: Text
              replace:
                rule: '.*{'
                dest-field: Text
                new-value: '{'
        - or-group:
            - name: 'Extract bytes and status'
              description: 'Extracting bytes and status from message field'
              enabled: true
              source-field: Text
              extract:
                rule: '.*'
            - name: 'Worker to category'
              enabled: true
              source-field: worker
              json-extract:
                dest-field: Category

    - name: 'Operator Test Rules 2'
      matcher: {}
      enabled: false
      and-sequence:
        - or-group:
            - name: 'Block 28000'
              description: 'Block 28000 pg error'
              enabled: true
              source-field: field1
              block:
                rule: 'sql_error_code\s*=\s*28000'
                keep-blocked-logs: false
```

### Communication

- Communicates with the Kubernetes cluster on the _Kubernetes REST API_
- Manages rule group objects through the _Coralogix gRPC API_
- Implements a standard gRPC `Health` service for readyness/liveness checks on port `9090`
- Exposes Prometheus metrics on HTTP on port `8080`

### Installation instructions

First add `cx-opeator`'s helm repository:

```shell
$ helm repo add cx-operator https://coralogix.github.io/cx-operator/
```

Check if it can find the `cx-operator` chart:

```shell
$ helm search repo cx-operator
NAME                   	CHART VERSION	APP VERSION	DESCRIPTION
cx-operator/cx-operator	0.1.0        	0.1.0      	Coralogix Kubernetes Operator
```

The Coralogix gRPC API requires an _API TOKEN_ which must be stored under the `coralogix-operator-secrets` K8s secret's `RULES_API_TOKEN` key.

First create the token on the Coralogix page:
In Settings –> Account, Choose ‘Alerts API Access’ option and generate new Alerts & Rules API key.

Then store the generated token in a Kubernetes secret:

```shell
$ kubectl create secret generic coralogix-operator-secrets --from-literal=RULES_API_TOKEN=00000000-0000-0000-0000-000000000000
```

Install the operator with the following helm command:

For accounts in Europe:
```shell
$ helm install --set config.coralogixApi.host=grpc-api.coralogix.com cx-operator cx-operator/cx-operator
```
For accounts in India:
```shell
$ helm install --set config.coralogixApi.host=grpc-api.app.coralogix.in cx-operator cx-operator/cx-operator
```

To also install a _Prometheus_ `ServiceMonitor` object, add: `--set serviceMonitor.create=true`
```

### Links
- [Rule group set CRD](https://github.com/coralogix/cx-operator/blob/master/crds/crd-coralogix-rule-group-set.yaml)

