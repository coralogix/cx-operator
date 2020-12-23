{{/* vim: set filetype=mustache: */}}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define ".chart.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define ".chart.resetImage" -}}
  {{- if eq .Values.env "development" -}}
    {{- printf "kubectl:%s" .Values.resetImage.tag | trimSuffix "-" -}}
  {{- else -}}
    {{- if eq ((printf "%T" .Values.resetImage.tag )) "float64" -}}
      {{- printf "%s/kubectl:%.f" .Values.resetImage.registry .Values.resetImage.tag | trimSuffix "-" -}}
    {{- else -}}
      {{- printf "%s/kubectl:%s" .Values.resetImage.registry .Values.resetImage.tag | trimSuffix "-" -}}
    {{- end -}}
  {{- end -}}
{{- end -}}

{{- define ".chart.serviceName" -}}
{{ .Chart.Name }}-service
{{- end -}}

{{- define ".chart.servicePort" -}}
    {{- if .Values.service.port -}}
        {{- .Values.service.port -}}
    {{- else -}}
        {{- .Values.network.port -}}
    {{- end -}}
{{- end -}}

{{- define ".chart.serviceAccount.name" -}}
    {{- if .Values.serviceAccount.name -}}
        {{- .Values.serviceAccount.name -}}
    {{- else -}}
        {{- .Chart.Name -}}-service-account
    {{- end -}}
{{- end -}}
