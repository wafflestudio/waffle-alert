package com.wafflestudio.alert.source.oci

// TODO: OCI Monitoring `summarize-metrics-data` API 조회 (namespace: oci_mysql_database)
//   - 1차 MVP metric: CPUUtilization(%), MemoryUtilization(%), DbVolumeUtilization(%),
//     CurrentConnections(count), ActiveConnections(count), BackupFailure(0=OK/1=FAILED)
//   - CPU/Memory는 resourceType=mysql / resourceType=heatwave 둘 다 반환될 수 있음
//     -> MVP는 resourceType=mysql만 대상 (heatwave는 2차)
//   - window의 max가 아니라 최신 aggregated datapoint 기준으로 조회 (resolved 지연 방지)
//   - 응답 -> AlertEvent 매핑: metricName=response.name, resourceId/resourceName=dimensions.*,
//     observedAt=최신 datapoint 시각, value=최신 datapoint 값,
//     labels.namespace/compartmentId=response.namespace/compartment-id, annotations.query=MQL 쿼리문
//   - 대상 DB System은 application.yml의 alert.oci-monitoring.mysql.db-systems 목록에서 읽음
