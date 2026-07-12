package com.wafflestudio.alert.source.scheduler

// TODO: @Scheduled - OciMonitoringAdapter 주기 조회 -> OciResourceEvaluator -> AlertIngestionService.ingest(event)
//   - polling-interval: 1m, query-window: 5m, resolution: 1m (application.yml alert.oci-monitoring 참고)
//   - enabled=false인 db-system은 스킵
