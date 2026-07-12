package com.wafflestudio.alert.domain.evaluator

// TODO: MySQL DB System 6개 rule threshold 판단 -> AlertEvent
//   - cpu-utilization / memory-utilization / db-volume-utilization: warning 80%, critical 90%
//   - current-connections: warning 500, critical 800 (count, percentage 아님)
//   - active-connections: warning 50, critical 100 (count)
//   - backup-failure: critical 1 (status)
//   - threshold는 application.yml의 db-system별 thresholds 설정에서 읽음
//   - fingerprint 포맷: "oci-monitoring:mysql:{dbSystemId}:{ruleName}"
//     예) oci-monitoring:mysql:ocid1.mysqldbsystem...:cpu-utilization-high
//   - status는 MVP 범위(FIRING/RESOLVED)만 판단. REPEATED 여부는 이후 IncidentService가 결정
//   - StatementLatency/Statements/NetworkBytes/DbVolumeReadWriteBytes/BackupTime/BackupSize는 2차로 보류
