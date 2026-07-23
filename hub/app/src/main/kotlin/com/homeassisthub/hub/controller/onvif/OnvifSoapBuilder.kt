package com.homeassisthub.hub.controller.onvif

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Builds ONVIF SOAP 1.2 envelopes with a WS-Security UsernameToken
 * (PasswordDigest), as required to authenticate PTZ requests against
 * http://[IP]:8899/onvif/device_service.
 */
object OnvifSoapBuilder {

    fun continuousMove(profileToken: String, panX: Double, tiltY: Double, username: String, password: String): String {
        val body = """
            <tptz:ContinuousMove xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
              <tptz:ProfileToken>$profileToken</tptz:ProfileToken>
              <tptz:Velocity>
                <tt:PanTilt xmlns:tt="http://www.onvif.org/ver10/schema" x="$panX" y="$tiltY"/>
              </tptz:Velocity>
            </tptz:ContinuousMove>
        """.trimIndent()
        return envelope(body, username, password)
    }

    fun stop(profileToken: String, username: String, password: String): String {
        val body = """
            <tptz:Stop xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
              <tptz:ProfileToken>$profileToken</tptz:ProfileToken>
              <tptz:PanTilt>true</tptz:PanTilt>
              <tptz:Zoom>true</tptz:Zoom>
            </tptz:Stop>
        """.trimIndent()
        return envelope(body, username, password)
    }

    fun getSnapshot(profileToken: String, username: String, password: String): String {
        val body = """
            <trt:GetSnapshot xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
              <trt:ProfileToken>$profileToken</trt:ProfileToken>
            </trt:GetSnapshot>
        """.trimIndent()
        return envelope(body, username, password)
    }

    fun getSnapshotUri(profileToken: String, username: String, password: String): String {
        val body = """
            <trt:GetSnapshotUri xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
              <trt:ProfileToken>$profileToken</trt:ProfileToken>
            </trt:GetSnapshotUri>
        """.trimIndent()
        return envelope(body, username, password)
    }

    private fun envelope(bodyXml: String, username: String, password: String): String {
        val created = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val nonceBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonceBase64 = Base64.getEncoder().encodeToString(nonceBytes)
        val digest = passwordDigest(nonceBytes, created, password)

        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
              <soap:Header>
                <Security xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
                  <UsernameToken>
                    <Username>$username</Username>
                    <Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</Password>
                    <Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceBase64</Nonce>
                    <Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created>
                  </UsernameToken>
                </Security>
              </soap:Header>
              <soap:Body>
                $bodyXml
              </soap:Body>
            </soap:Envelope>
        """.trimIndent()
    }

    private fun passwordDigest(nonceBytes: ByteArray, created: String, password: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(nonceBytes)
        sha1.update(created.toByteArray(Charsets.UTF_8))
        sha1.update(password.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sha1.digest())
    }
}
