package com.wafflestudio.alert.source.oci

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/** alert.oci.* — OCI SDK 인증/리전 설정 (Cost·Monitoring 공용) */
@Configuration
@ConfigurationProperties(prefix = "alert.oci")
class OciProperties {
    var authType: AuthType = AuthType.CONFIG_FILE
    var configFilePath: String = "~/.oci/config"
    var profile: String = "DEFAULT"
    var tenantId: String = "" // ocid1.tenancy.oc1..xxxx
    var region: String = "ap-chuncheon-1"

    enum class AuthType { CONFIG_FILE, INSTANCE_PRINCIPAL }
}
