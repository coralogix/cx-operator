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
              rule: '.*{'
              replace:
                dest-field: Text
                new-value: '{'
        - or-group:
            - name: 'Extract bytes and status'
              description: 'Extracting bytes and status from message field'
              enabled: true
              source-field: Text
              rule: '.*'
              extract: {}
            - name: 'Worker to category'
              enabled: true
              source-field: worker
              rule: '.*'
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
              rule: 'sql_error_code\s*=\s*28000'
              block:
                keep-blocked-logs: false
```

### Communication

- Communicates with the Kubernetes cluster on the _Kubernetes REST API_
- Manages rule group objects through the _Coralogix gRPC API_
- Implements a standard gRPC `Health` service for readyness/liveness checks on port `9090`
- Exposes Prometheus metrics on HTTP on port `8080`

### Installation instructions
TODO

#### Configuration
- The Coralogix gRPC API requires an API TOKEN which must be stored under the `coralogix-operator-secrets` K8s secret's `RULES_API_TOKEN` key.

### Links
- [Rule group set CRD](https://github.com/coralogix/cx-operator/blob/master/crds/crd-coralogix-rule-group-set.yaml)

